package in.ilovemyfiles.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryGuardedCacheManagerTest {

    private final RedisMemoryGuard guard = mock(RedisMemoryGuard.class);
    private final Cache cache =
            new MemoryGuardedCacheManager(new ConcurrentMapCacheManager("files"), guard).getCache("files");

    @Test
    void writesWhileRedisHasRoom() {
        when(guard.hasRoom()).thenReturn(true);

        cache.put("u1:root", "listing");

        assertEquals("listing", cache.get("u1:root").get());
    }

    @Test
    void skipsWritesOnceRedisIsFull() {
        when(guard.hasRoom()).thenReturn(false);

        cache.put("u1:root", "listing");
        cache.putIfAbsent("u2:root", "listing");

        assertNull(cache.get("u1:root"));
        assertNull(cache.get("u2:root"));
    }

    @Test
    void stillEvictsWhenRedisIsFull() {
        when(guard.hasRoom()).thenReturn(true);
        cache.put("u1:root", "listing");
        when(guard.hasRoom()).thenReturn(false);

        cache.evict("u1:root");

        assertNull(cache.get("u1:root"));
    }

    @Test
    void returnsLoadedValuesWithoutStoringThemWhenFull() {
        when(guard.hasRoom()).thenReturn(false);

        assertEquals("from database", cache.get("u1:root", () -> "from database"));
        assertNull(cache.get("u1:root"));
    }
}
