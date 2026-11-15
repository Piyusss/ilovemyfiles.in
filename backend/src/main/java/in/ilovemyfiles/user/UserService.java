package in.ilovemyfiles.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.ilovemyfiles.common.NotFoundException;
import in.ilovemyfiles.common.StorageQuotaException;
import in.ilovemyfiles.config.AdminAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AdminAccess adminAccess;

    @Value("${app.max-storage-per-user:75MB}")
    private DataSize maxStoragePerUser;

    // Clerk user id -> our user id. The mapping never changes once created, so
    // an in-memory cache can't go stale, and it saves a database round trip on
    // every authenticated request (REST and WebSocket).
    private final Cache<String, UUID> userIdByClerkId = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    // ── Storage quota ─────────────────────────────────────────────────────────

    /** The account's limit: its own storage_limit, capped by app.max-storage-per-user. */
    public long storageLimit(User user) {
        return Math.min(user.getStorageLimit(), maxStoragePerUser.toBytes());
    }

    /**
     * What counts against the limit: everything the account keeps in storage,
     * including trash and older versions (see UserRepository#storedBytes). The
     * storage_used column only tracks live files, so it isn't used for this.
     */
    public long storedBytes(UUID userId) {
        return userRepository.storedBytes(userId);
    }

    /**
     * Throws when storing {@code extraBytes} more would put the account over its
     * limit. {@code reservedBytes} is space the user's unfinished uploads already
     * hold. Counting it stops one account from reserving far more than its quota
     * and, with it, the app-wide capacity every user shares.
     */
    public void checkQuota(User user, long extraBytes, long reservedBytes) {
        long limit = storageLimit(user);
        long used = storedBytes(user.getId()) + reservedBytes;
        if (used + extraBytes > limit) {
            String held = reservedBytes > 0 ? " (" + mb(reservedBytes) + " is held by uploads in progress)" : "";
            throw new StorageQuotaException("Not enough space: " + mb(Math.max(0, limit - used))
                    + " of your " + mb(limit) + " is free" + held);
        }
    }

    public static String mb(long bytes) {
        double value = bytes / (1024.0 * 1024.0);
        return (value == Math.floor(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value)) + " MB";
    }

    public User findById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    /** Cached user profile — served from Redis for up to 5 minutes. */
    @Cacheable(value = "user-dto", key = "#userId")
    public UserDto getUserDto(UUID userId) {
        return toDto(findById(userId));
    }

    /**
     * Our user id for a Clerk user, creating the account on first sign-in.
     * Called on every authenticated request; after the first lookup it is
     * answered from memory. Concurrent first requests for the same Clerk user
     * (the app fires several at once right after sign-up) wait for one lookup
     * instead of racing to insert the same row.
     */
    public UUID resolveUserId(String clerkId, String email, String fullName) {
        return userIdByClerkId.get(clerkId, id -> findOrCreateByClerkId(id, email, fullName).getId());
    }

    /**
     * Looks the user up by Clerk ID, links a pre-existing account with the same
     * email, or creates the account. Deliberately not one transaction: each step
     * commits on its own, so when another server instance inserts the same user
     * first, the unique constraint rejects our write and we read theirs.
     */
    public User findOrCreateByClerkId(String clerkId, String email, String fullName) {
        // 1. Already linked to this Clerk account
        var byClerk = userRepository.findByClerkId(clerkId);
        if (byClerk.isPresent()) return byClerk.get();

        String resolvedEmail = (email != null && !email.isBlank())
                ? email.toLowerCase().trim()
                : clerkId + "@ilovemyfiles.user";

        try {
            // 2. Pre-existing account with this email (e.g. from old custom-auth) → link it
            if (email != null && !email.isBlank()) {
                var byEmail = userRepository.findByEmail(resolvedEmail);
                if (byEmail.isPresent()) {
                    User existing = byEmail.get();
                    existing.setClerkId(clerkId);
                    if (fullName != null && existing.getFullName() == null) existing.setFullName(fullName);
                    return userRepository.saveAndFlush(existing);
                }
            }

            // 3. Brand-new user
            return userRepository.saveAndFlush(User.builder()
                    .clerkId(clerkId)
                    .email(resolvedEmail)
                    .fullName(fullName)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Created concurrently by another instance: use that row.
            return userRepository.findByClerkId(clerkId).orElseThrow(() -> e);
        }
    }

    /**
     * Every file operation (upload, delete, restore) calls this to adjust quota.
     * Evict the user-dto cache so the next /me request reflects the true usage.
     */
    @Transactional
    @CacheEvict(value = "user-dto", key = "#userId")
    public void updateStorageUsed(UUID userId, long delta) {
        User user = findById(userId);
        long newUsage = Math.max(0, user.getStorageUsed() + delta);
        user.setStorageUsed(newUsage);
        userRepository.save(user);
    }

    public UserDto toDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .storageUsed(storedBytes(user.getId()))
                .storageLimit(storageLimit(user))
                .createdAt(user.getCreatedAt())
                .admin(adminAccess.isAdmin(user.getClerkId()))
                .build();
    }
}
