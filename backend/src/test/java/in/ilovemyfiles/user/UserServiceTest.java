package in.ilovemyfiles.user;

import in.ilovemyfiles.common.StorageQuotaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
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
}
