package in.ilovemyfiles.user;

import in.ilovemyfiles.common.NotFoundException;
import in.ilovemyfiles.common.StorageQuotaException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Value("${app.max-storage-per-user:75MB}")
    private DataSize maxStoragePerUser;

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

    /** Throws when storing {@code extraBytes} more would put the account over its limit. */
    public void checkQuota(User user, long extraBytes) {
        long limit = storageLimit(user);
        long stored = storedBytes(user.getId());
        if (stored + extraBytes > limit) {
            throw new StorageQuotaException("Not enough space: " + mb(Math.max(0, limit - stored))
                    + " of your " + mb(limit) + " is free");
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
     * Called on every authenticated request.
     * Looks up the user by Clerk ID; creates the record on first sign-in.
     */
    @Transactional
    public User findOrCreateByClerkId(String clerkId, String email, String fullName) {
        // 1. Already linked to this Clerk account
        var byClerk = userRepository.findByClerkId(clerkId);
        if (byClerk.isPresent()) return byClerk.get();

        String resolvedEmail = (email != null && !email.isBlank())
                ? email.toLowerCase().trim()
                : clerkId + "@ilovemyfiles.user";

        // 2. Pre-existing account with this email (e.g. from old custom-auth) → link it
        if (email != null && !email.isBlank()) {
            var byEmail = userRepository.findByEmail(resolvedEmail);
            if (byEmail.isPresent()) {
                User existing = byEmail.get();
                existing.setClerkId(clerkId);
                if (fullName != null && existing.getFullName() == null) existing.setFullName(fullName);
                return userRepository.save(existing);
            }
        }

        // 3. Brand-new user
        User user = User.builder()
                .clerkId(clerkId)
                .email(resolvedEmail)
                .fullName(fullName)
                .build();
        return userRepository.save(user);
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
                .build();
    }
}
