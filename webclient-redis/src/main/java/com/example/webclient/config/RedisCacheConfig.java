package com.example.webclient.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

// ─────────────────────────────────────────────────────────────────────────────
// RedisCacheConfig — configures how cache values are serialized into Redis.
//
// WHY THIS CLASS EXISTS:
//   By default, Spring's Redis cache uses JDK serialization, which requires
//   every cached type to implement java.io.Serializable and stores opaque
//   binary blobs. We instead use JSON serialization, which:
//     - does not require Serializable on the model classes
//     - stores human-readable JSON in Redis (easy to inspect with redis-cli)
//     - is resilient to small class changes
//
// This is the ONLY code difference between the Redis and Caffeine modules.
// The @Cacheable annotations on the service are identical in both.
// ─────────────────────────────────────────────────────────────────────────────
@Configuration
public class RedisCacheConfig {

    // Customizes the auto-configured RedisCacheManager so that every cache
    // serializes its values as JSON (via Jackson) instead of JDK binary.
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheCustomizer(ObjectMapper objectMapper) {
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                // TTL also set in application.yml; kept here so the serializer
                // config and the expiry live together.
                .entryTtl(Duration.ofSeconds(60))
                .disableCachingNullValues()
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        return builder -> builder
                .cacheDefaults(defaults)
                .withCacheConfiguration("posts", defaults)
                .withCacheConfiguration("postById", defaults);
    }
}
