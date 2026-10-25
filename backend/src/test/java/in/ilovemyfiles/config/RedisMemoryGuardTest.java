package in.ilovemyfiles.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.util.unit.DataSize;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisMemoryGuardTest {

    private static final long MB = 1024L * 1024;

    @Mock RedisConnectionFactory factory;
    @Mock RedisConnection connection;
    @Mock RedisServerCommands server;

    private long now = 0;
    private RedisMemoryGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RedisMemoryGuard(factory, DataSize.ofMegabytes(24), () -> now);
    }

    private void redisReports(Properties info) {
        when(factory.getConnection()).thenReturn(connection);
        when(connection.serverCommands()).thenReturn(server);
        when(server.info("memory")).thenReturn(info);
    }

    private void redisUses(long bytes) {
        Properties info = new Properties();
        info.setProperty("used_memory", String.valueOf(bytes));
        redisReports(info);
    }

    @Test
    void hasRoomBelowTheLimit() {
        redisUses(2 * MB); // an empty Redis Cloud database reports about 2 MB

        assertTrue(guard.hasRoom());
    }

    @Test
    void hasNoRoomOnceTheLimitIsReached() {
        redisUses(24 * MB);

        assertFalse(guard.hasRoom());
    }

    @Test
    void readsRedisAtMostEveryFiveSeconds() {
        redisUses(2 * MB);

        guard.hasRoom();
        now = 4_999;
        guard.hasRoom();
        verify(server, times(1)).info("memory");

        now = 5_000;
        guard.hasRoom();
        verify(server, times(2)).info("memory");
    }

    @Test
    void hasNoRoomWhenRedisCannotBeReached() {
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("Unable to connect"));

        assertFalse(guard.hasRoom());
    }

    @Test
    void hasNoRoomWhenTheFigureIsMissing() {
        redisReports(new Properties());

        assertFalse(guard.hasRoom());
    }
}
