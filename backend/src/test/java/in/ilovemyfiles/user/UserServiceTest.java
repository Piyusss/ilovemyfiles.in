package in.ilovemyfiles.user;

import in.ilovemyfiles.common.StorageQuotaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final long MB = 1024L * 1024;

    @Mock UserRepository userRepository;

    @InjectMocks UserService userService;

    private final User user = new User();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(userService, "maxStoragePerUser", DataSize.ofMegabytes(75));
        user.setId(UUID.randomUUID());
        user.setStorageLimit(5L * 1024 * MB); // the old 5 GB default still in the database
    }

    @Test
    void limitIsCappedAtTheConfiguredMaximum() {
        assertEquals(75 * MB, userService.storageLimit(user));

        user.setStorageLimit(20 * MB); // an account with a smaller limit keeps it
        assertEquals(20 * MB, userService.storageLimit(user));
    }

    @Test
    void allowsAnUploadThatFillsTheAccountExactly() {
        when(userRepository.storedBytes(user.getId())).thenReturn(70 * MB);

        assertDoesNotThrow(() -> userService.checkQuota(user, 5 * MB));
    }

    @Test
    void rejectsAnUploadThatWouldGoOverTheLimit() {
        when(userRepository.storedBytes(user.getId())).thenReturn(70 * MB + MB / 2);

        var ex = assertThrows(StorageQuotaException.class, () -> userService.checkQuota(user, 5 * MB));
        assertEquals("Not enough space: 4.5 MB of your 75 MB is free", ex.getMessage());
    }

    // ── Clerk id -> user id ──────────────────────────────────────────────────

    @Test
    void answersRepeatLookupsFromMemory() {
        User stored = User.builder().id(UUID.randomUUID()).clerkId("user_1").email("a@x.com").build();
        when(userRepository.findByClerkId("user_1")).thenReturn(Optional.of(stored));

        for (int i = 0; i < 5; i++) {
            assertEquals(stored.getId(), userService.resolveUserId("user_1", "a@x.com", "A"));
        }
        // Used to be one database query per authenticated request.
        verify(userRepository, times(1)).findByClerkId("user_1");
    }

    @Test
    void parallelFirstRequestsCreateTheUserOnce() throws Exception {
        when(userRepository.findByClerkId("user_new")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@x.com")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            Thread.sleep(50); // widen the window the old code raced in
            User created = inv.getArgument(0);
            created.setId(UUID.randomUUID());
            return created;
        });

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<UUID>> futures = new ArrayList<>();
            Callable<UUID> firstRequest = () -> userService.resolveUserId("user_new", "new@x.com", "New");
            for (int i = 0; i < 8; i++) futures.add(pool.submit(firstRequest));
            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> future : futures) ids.add(future.get());

            assertEquals(1, ids.size());
            verify(userRepository, times(1)).saveAndFlush(any(User.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void usesTheRowAnotherInstanceInsertedFirst() {
        User theirs = User.builder().id(UUID.randomUUID()).clerkId("user_race").email("r@x.com").build();
        when(userRepository.findByClerkId("user_race")).thenReturn(Optional.empty(), Optional.of(theirs));
        when(userRepository.findByEmail("r@x.com")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates users_clerk_id_key"));

        assertEquals(theirs.getId(), userService.resolveUserId("user_race", "r@x.com", "R"));
    }
}
