package in.ilovemyfiles.storage;

import in.ilovemyfiles.common.StorageQuotaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageCapacityServiceTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Mock JdbcTemplate jdbc;

    @InjectMocks StorageCapacityService service;

    @BeforeEach
    void setCeiling() {
        ReflectionTestUtils.setField(service, "maxTotalStorage", DataSize.ofGigabytes(9));
    }

    private void usage(long stored, long inFlight) {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(stored, inFlight);
    }

    @Test
    void allowsAnUploadThatReachesTheCeilingExactly() {
        usage(8 * GB, GB / 2);

        assertDoesNotThrow(() -> service.checkCapacity(GB / 2));
    }

    @Test
    void refusesAnUploadThatWouldPassTheCeiling() {
        usage(8 * GB, GB / 2);

        var ex = assertThrows(StorageQuotaException.class, () -> service.checkCapacity(GB / 2 + 1));
        assertTrue(ex.getMessage().startsWith("Uploads are paused"));
    }

    @Test
    void countsUploadsInProgress() {
        usage(5 * GB, 4 * GB); // 9 GB already stored or on its way

        assertThrows(StorageQuotaException.class, () -> service.checkCapacity(1));
    }

    @Test
    void takesTheLockBeforeMeasuring() {
        usage(0, 0);

        service.checkCapacity(1);

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).execute(startsWith("SELECT pg_advisory_xact_lock("));
        order.verify(jdbc, times(2)).queryForObject(anyString(), eq(Long.class));
    }
}
