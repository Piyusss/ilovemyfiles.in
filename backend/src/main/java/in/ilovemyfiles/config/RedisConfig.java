package in.ilovemyfiles.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Map;

@Configuration
@Slf4j
public class RedisConfig implements CachingConfigurer {

    /**
     * Redis is only a cache, so an outage must not fail requests. A failed
     * read is treated as a miss (the method runs against the database), and
     * failed writes and evictions are logged and skipped.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                logCacheError("read", cache, key, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                logCacheError("write", cache, key, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                logCacheError("evict", cache, key, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                logCacheError("clear", cache, "*", e);
            }
        };
    }

    private static void logCacheError(String op, Cache cache, Object key, RuntimeException e) {
        log.warn("Cache {} failed for {}::{}, falling back to the database: {}",
                op, cache.getName(), key, e.getMessage());
    }

    // Taken as a String and parsed here so it also works outside Spring Boot's
    // property conversion (e.g. in plain Spring test contexts).
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory,
                                     @Value("${app.redis-memory-limit:24MB}") String redisMemoryLimit) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType(Object.class).build(),
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY);

        var serializer = new GenericJackson2JsonRedisSerializer(mapper);
        var valueSerializer = RedisSerializationContext.SerializationPair.fromSerializer(serializer);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(valueSerializer)
                .disableCachingNullValues()
                .prefixCacheNameWith("ilovemyfiles:");

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                "user-dto",  base.entryTtl(Duration.ofMinutes(5)),
                "files",     base.entryTtl(Duration.ofMinutes(5)),
                "files-all", base.entryTtl(Duration.ofMinutes(5)),
                "folders",   base.entryTtl(Duration.ofMinutes(10))
        );

        RedisCacheManager redisCacheManager = RedisCacheManager.builder(factory)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(5)))
                .withInitialCacheConfigurations(perCache)
                .build();
        // Not a bean itself, so initialise it here (applies the per-cache TTLs above).
        redisCacheManager.afterPropertiesSet();

        return new MemoryGuardedCacheManager(redisCacheManager,
                new RedisMemoryGuard(factory, DataSize.parse(redisMemoryLimit)));
    }
}
