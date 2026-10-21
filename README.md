# REST API + WebClient + Resilience4j — Caching strategies (Caffeine vs Redis)

Multi-module Maven project that demonstrates the **same** REST API + WebClient +
Resilience4j application with **two different caching strategies**. The goal is
to show that Spring Cache is an abstraction: the `@Cacheable` code is identical
in both modules — only the cache provider (dependency + configuration) changes.

```
rest-api-webclient-cache/           ← parent POM (packaging = pom)
├── webclient-caffeine/             ← in-memory cache (Caffeine) — no external infra
└── webclient-redis/                ← distributed cache (Redis) — shared, needs a server
```

---

## What the application does

Both modules run the identical app:

1. **Expose a REST API** — `POST /api/v1/products` (we are the server).
2. **Consume an external API** — `GET /api/v1/posts` via WebClient, protected by
   Resilience4j `@Retry` + `@CircuitBreaker` with fallbacks.
3. **Cache the external responses** — `@Cacheable` so repeated reads are instant
   and the external API is shielded.

See each module for the full endpoint and resilience documentation.

---

## Caffeine vs Redis — when to use which

| | **webclient-caffeine** | **webclient-redis** |
|---|---|---|
| Where the cache lives | Inside the JVM (in memory) | A separate Redis server |
| External infrastructure | None — runs out of the box | Requires a running Redis |
| Shared across app instances | No (per-JVM) | Yes (all instances share it) |
| Survives an app restart | No | Yes |
| Best for | Single instance, simple setups | Multiple instances behind a load balancer |
| Serialization | Java objects in memory | JSON (via `RedisCacheConfig`) |

The `@Cacheable` annotations in `ExternalApiService` are **the same** in both
modules. The only differences are:

- **Dependency:** `caffeine` vs `spring-boot-starter-data-redis`
- **Config:** `spring.cache.caffeine.spec` vs `spring.cache.type: redis` + `spring.data.redis.*`
- **Redis only:** a small `RedisCacheConfig` to store values as JSON instead of JDK binary

---

## Build

```bash
# Build both modules
mvn clean package

# Build a single module
mvn clean package -pl webclient-caffeine
mvn clean package -pl webclient-redis
```

Requirements: Java 17, Maven 3.8+. (Docker only needed to run the Redis module.)

---

## Run the Caffeine module (no infrastructure)

```bash
cd webclient-caffeine
mvn spring-boot:run
# App on http://localhost:8080
```

Verify the cache (2nd call is a HIT, much faster):

```bash
curl http://localhost:8080/api/v1/posts/1   # cache MISS — hits the network (~1s)
curl http://localhost:8080/api/v1/posts/1   # cache HIT  — served from memory (~10ms)
```

Measured locally: **MISS ~1153 ms → HIT ~12 ms**.

---

## Run the Redis module (needs Docker)

```bash
cd webclient-redis

# 1. Start Redis (and an optional web UI on http://localhost:8081)
docker-compose up -d

# 2. Start the app (reads REDIS_HOST/REDIS_PORT, defaults to localhost:6379)
mvn spring-boot:run
# App on http://localhost:8080
```

Verify the cache the same way:

```bash
curl http://localhost:8080/api/v1/posts/1   # cache MISS — stored in Redis
curl http://localhost:8080/api/v1/posts/1   # cache HIT  — served from Redis
```

Inspect the cached keys:

```bash
docker exec -it webclient-redis redis-cli KEYS '*'
docker exec -it webclient-redis redis-cli GET "postById::1"
# or open the web UI at http://localhost:8081
```

Stop Redis:

```bash
docker-compose down
```

### Redis environment variables

| Variable | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |

For a managed Redis in production (e.g. AWS ElastiCache), point `REDIS_HOST`
to the cluster endpoint — no code changes required.

---

## Performance note — Virtual Threads (Java 21)

Both modules are I/O-bound (every `/posts` call blocks on the external API).
On **Java 21**, enabling virtual threads is another way to boost performance:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Each request then runs on a lightweight JVM-managed thread, so the app can
handle thousands of concurrent blocking calls without exhausting the Tomcat
pool. The modules target Java 17, so this is left as a documented note in each
`application.yml` (change `<java.version>` to 21 in the parent POM first).
