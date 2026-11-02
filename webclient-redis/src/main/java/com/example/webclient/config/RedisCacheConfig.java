package com.example.webclient.config;

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
//
// ─────────────────────────────────────────────────────────────────────────────
// WHY WE DO NOT INJECT SPRING'S OWN ObjectMapper HERE  (important!)
//
//   To read a value back from Redis as a Post (and not as a generic
//   LinkedHashMap), the JSON must carry the concrete type. Jackson does this
//   with "default typing": it writes an "@class" property into the JSON, e.g.
//       {"@class":"com.example.webclient.model.Post","userId":1,...}
//   On read, Jackson sees "@class" and rebuilds the exact Post type.
//
//   GenericJackson2JsonRedisSerializer enables default typing ONLY when it
//   builds its OWN ObjectMapper (the no-arg constructor). If you pass in
//   Spring's shared ObjectMapper — which does NOT have default typing — the
//   "@class" hint is never written, so on read Jackson falls back to
//   LinkedHashMap and you get:
//
//       java.lang.ClassCastException:
//           class java.util.LinkedHashMap cannot be cast to ... Post
//
//   Fix: use the no-arg constructor so the serializer configures a dedicated
//   ObjectMapper with the type info enabled.
// ─────────────────────────────────────────────────────────────────────────────
@Configuration
public class RedisCacheConfig {

    // Customizes the auto-configured RedisCacheManager so that every cache
    // serializes its values as JSON (via Jackson) instead of JDK binary.
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheCustomizer() {
        // No-arg constructor → the serializer builds its own ObjectMapper with
        // default typing enabled, so each JSON value carries its "@class" and
        // can be deserialized back into the exact type (e.g. Post).
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer();

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
