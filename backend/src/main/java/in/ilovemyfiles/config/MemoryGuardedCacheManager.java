package in.ilovemyfiles.config;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Wraps the Redis cache manager so new entries are only written while Redis has
 * room (see {@link RedisMemoryGuard}). Reads and evictions always go through, so
 * a full cache only means more database reads until older entries expire.
 */
public class MemoryGuardedCacheManager implements CacheManager {

    private final CacheManager delegate;
    private final RedisMemoryGuard guard;
    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();

    public MemoryGuardedCacheManager(CacheManager delegate, RedisMemoryGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = delegate.getCache(name);
        return cache == null ? null : caches.computeIfAbsent(name, n -> new GuardedCache(cache, guard));
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    static class GuardedCache implements Cache {

        private final Cache delegate;
        private final RedisMemoryGuard guard;

        GuardedCache(Cache delegate, RedisMemoryGuard guard) {
            this.delegate = delegate;
            this.guard = guard;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            return delegate.get(key);
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return delegate.get(key, type);
        }

        // Loads through this wrapper (not the delegate) so the write is guarded too.
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Callable<T> valueLoader) {
            ValueWrapper hit = delegate.get(key);
            if (hit != null) {
                return (T) hit.get();
            }
            T value;
            try {
                value = valueLoader.call();
            } catch (Exception e) {
                throw new ValueRetrievalException(key, valueLoader, e);
            }
            put(key, value);
            return value;
        }

        @Override
        public CompletableFuture<?> retrieve(Object key) {
            return delegate.retrieve(key);
        }

        @Override
        public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
            return delegate.retrieve(key, valueLoader);
        }

        @Override
        public void put(Object key, Object value) {
            if (guard.hasRoom()) {
                delegate.put(key, value);
            }
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            return guard.hasRoom() ? delegate.putIfAbsent(key, value) : delegate.get(key);
        }

        @Override
        public void evict(Object key) {
            delegate.evict(key);
        }

        @Override
        public boolean evictIfPresent(Object key) {
            return delegate.evictIfPresent(key);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public boolean invalidate() {
            return delegate.invalidate();
        }
    }
}
