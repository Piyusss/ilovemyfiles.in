package in.ilovemyfiles.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.util.unit.DataSize;

import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Tells the cache whether Redis has room for new entries, so the app stays well
 * below the database's memory size (30 MB on Redis Cloud's free plan). Reads
 * {@code used_memory}, the figure Redis Cloud shows against that size, at most
 * every few seconds. When the value can't be read, it reports no room.
 */
@Slf4j
public class RedisMemoryGuard {

    static final long SAMPLE_INTERVAL_MS = 5_000;

    private final RedisConnectionFactory connectionFactory;
    private final long limitBytes;
    private final LongSupplier clock;

    // Only one request samples at a time; the others use the last value meanwhile.
    private final ReentrantLock sampling = new ReentrantLock();
    private volatile boolean sampled;
    private volatile long sampledAt;
    private volatile long usedBytes = Long.MAX_VALUE; // unknown until the first sample
    private volatile boolean unreadable;

    public RedisMemoryGuard(RedisConnectionFactory connectionFactory, DataSize limit) {
        this(connectionFactory, limit, System::currentTimeMillis);
    }

    RedisMemoryGuard(RedisConnectionFactory connectionFactory, DataSize limit, LongSupplier clock) {
        this.connectionFactory = connectionFactory;
        this.limitBytes = limit.toBytes();
        this.clock = clock;
    }

    /** True while Redis reports less memory in use than the limit. */
    public boolean hasRoom() {
        long now = clock.getAsLong();
        if ((!sampled || now - sampledAt >= SAMPLE_INTERVAL_MS) && sampling.tryLock()) {
            try {
                sample(now);
            } finally {
                sampling.unlock();
            }
        }
        return usedBytes < limitBytes;
    }

    private void sample(long now) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Properties info = connection.serverCommands().info("memory");
            usedBytes = Long.parseLong(info.getProperty("used_memory").trim());
            unreadable = false;
        } catch (RuntimeException e) {
            usedBytes = Long.MAX_VALUE; // can't tell how full Redis is, so don't add to it
            if (!unreadable) {
                log.warn("Can't read Redis memory usage, pausing cache writes: {}", e.getMessage());
                unreadable = true;
            }
        } finally {
            sampledAt = now;
            sampled = true;
        }
    }
}
