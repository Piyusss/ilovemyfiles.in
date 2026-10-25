package in.ilovemyfiles.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the outage where the Redis host stopped existing: cached reads
 * must fall back to the method (the database, in the real services) instead
 * of failing the request, and evictions must not throw.
 */
@SpringJUnitConfig(RedisCacheOutageTest.Config.class)
class RedisCacheOutageTest {

    @Configuration
    @EnableCaching
    @Import(RedisConfig.class)
    static class Config {

        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            // ".invalid" is a reserved TLD that never resolves, like a deleted Upstash host.
            var server = new RedisStandaloneConfiguration("redis.invalid", 6379);
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build();
            return new LettuceConnectionFactory(server, client);
        }

        @Bean
        Lister lister() {
            return new Lister();
        }
    }

    static class Lister {
        private final AtomicInteger calls = new AtomicInteger();

        @Cacheable(value = "files", key = "#userId")
        public String list(String userId) {
            calls.incrementAndGet();
            return "files of " + userId;
        }

        // Read through a method: field access on the caching proxy wouldn't reach this instance.
        public int calls() {
            return calls.get();
        }

        @CacheEvict(value = "files", key = "#userId")
        public void evict(String userId) {
        }
    }

    @Autowired
    Lister lister;

    @Test
    void cachedReadFallsBackToTheMethodWhenRedisIsUnreachable() {
        int before = lister.calls();

        assertEquals("files of u1", lister.list("u1"));
        assertEquals("files of u1", lister.list("u1"));

        // Nothing can be cached, so every call runs the method.
        assertEquals(before + 2, lister.calls());
    }

    @Test
    void evictionDoesNotThrowWhenRedisIsUnreachable() {
        assertDoesNotThrow(() -> lister.evict("u1"));
    }
}
