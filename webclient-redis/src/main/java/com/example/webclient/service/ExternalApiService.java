package com.example.webclient.service;

import com.example.webclient.model.Post;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Collections;
import java.util.List;

// ─────────────────────────────────────────────────────────────────────────────
// ExternalApiService — consumes the external jsonplaceholder API.
//
// ANNOTATIONS USED IN THIS CLASS:
//
//   From Resilience4j (io.github.resilience4j):
//     @Retry          → retries the method call on failure before giving up
//     @CircuitBreaker → opens the circuit after too many failures
//
//   From Spring (org.springframework.stereotype):
//     @Service        → marks this class as a Spring-managed service bean
//
// ─────────────────────────────────────────────────────────────────────────────
// HOW RETRY WORKS — step by step
//
//   Retry is the FIRST line of defense. It wraps the method call and, if an
//   exception is thrown, waits and tries again before letting the failure
//   propagate to the Circuit Breaker.
//
//   Timeline for a single failing call (max-attempts=3, wait-duration=500ms):
//
//     t=0ms    → Attempt 1 → IOException thrown
//     t=500ms  → Attempt 2 → IOException thrown   (waited 500ms)
//     t=1000ms → Attempt 3 → IOException thrown   (waited 500ms again)
//     t=1000ms → All attempts exhausted → exception propagates to @CircuitBreaker
//
//   Key rule: retry-exceptions lists WHICH exceptions trigger a retry.
//   Exceptions NOT in the list (e.g. 400 Bad Request) are NOT retried because
//   retrying a bad request will never fix it — the data itself is wrong.
//
// ─────────────────────────────────────────────────────────────────────────────
// HOW CIRCUIT BREAKER WORKS — step by step
//
//   Circuit Breaker is the SECOND line of defense. It counts the failures that
//   come out of @Retry and, when there are too many, opens the circuit.
//
//   States:
//     CLOSED    → normal operation, calls go through to WebClient             ✅
//     OPEN      → circuit is open, calls go directly to the fallback method   ❌
//     HALF_OPEN → test mode, allows a few calls through to check recovery     🔄
//
//   Full call flow:
//
//     Caller → @Retry wraps the call
//                  ↓
//              @CircuitBreaker checks if circuit is CLOSED
//                  ↓
//              WebClient → External API
//                  ↓ (on failure)
//              @Retry retries up to max-attempts times
//                  ↓ (if all retries fail)
//              @CircuitBreaker records ONE failure
//                  ↓ (when failure-rate-threshold is crossed)
//              Circuit opens → ALL subsequent calls skip WebClient
//                  ↓
//              fallbackMethod() returns emergency response
//
//   Important: the entire retry cycle counts as ONE failure for the circuit breaker.
//   Retry=3 + CircuitBreaker threshold=5 means the circuit opens after
//   5 full retry cycles (15 actual HTTP attempts), not after 5 single calls.
// ─────────────────────────────────────────────────────────────────────────────
@Service
public class ExternalApiService {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiService.class);

    // This name must match the keys under resilience4j.retry.instances
    // and resilience4j.circuit-breaker.instances in application.yml
    private static final String CB_NAME = "externalApi";

    private final WebClient webClient;

    public ExternalApiService(WebClient webClient) {
        this.webClient = webClient;
    }

    // ─── GET all posts ────────────────────────────────────────────────────────
    //
    // Annotation: @Cacheable  (from org.springframework.cache.annotation)
    //   PERFORMANCE: caches the result under the "posts" cache. On a cache HIT,
    //   the method body is NEVER executed — no HTTP call, no retry, no circuit
    //   breaker evaluation. The cached List<Post> is returned immediately.
    //   This is the single biggest performance win for a read-heavy endpoint
    //   that calls a slow external API.
    //
    //   Aspect ordering: @Cacheable runs OUTSIDE @Retry/@CircuitBreaker, so a
    //   cache hit short-circuits everything. Only a cache MISS triggers the
    //   resilient WebClient call below.
    //
    //   Cache provider: Caffeine (in-memory, see application.yml -> spring.cache).
    //   In production with multiple instances, switch to Redis for a SHARED
    //   cache — the annotation stays identical (see pom.xml migration notes).
    //
    // [JAVA 21 PERFORMANCE NOTE] This method is I/O-bound (it blocks on an
    //   external HTTP call via .block()). On Java 21 you could enable Virtual
    //   Threads with `spring.threads.virtual.enabled=true` so each request runs
    //   on a lightweight virtual thread. That lets the app handle thousands of
    //   concurrent blocking calls without exhausting the Tomcat thread pool —
    //   another way to increase performance for this kind of workload.
    //
    // Annotation: @Retry  (from io.github.resilience4j.retry.annotation)
    //   Wraps this method. If an exception is thrown, Resilience4j intercepts it
    //   and retries the call up to max-attempts times before giving up.
    //   Only exceptions listed under retry-exceptions in application.yml trigger a retry.
    //
    // Annotation: @CircuitBreaker  (from io.github.resilience4j.circuitbreaker.annotation)
    //   Wraps this method. Counts how many times @Retry exhausts all attempts.
    //   When the failure rate crosses failure-rate-threshold → circuit opens.
    //   While OPEN, the method body is never executed — goes straight to fallbackMethod.
    //
    // WebClient chain:
    //   .get()                   → HTTP GET
    //   .uri("/posts")           → appended to base-url from application.yml
    //   .retrieve()              → execute the request
    //   .bodyToFlux(Post.class)  → deserialize JSON array into a reactive stream of Posts
    //   .collectList()           → collect the stream into List<Post>
    //   .block()                 → block the thread until complete
    //                              (acceptable here because we are in a Spring MVC context,
    //                               not a fully reactive pipeline)
    @Cacheable("posts")
    @Retry(name = CB_NAME)
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fallbackAllPosts")
    public List<Post> getAllPosts() {
        log.info("Calling external API: GET /posts  (cache MISS — hitting the network)");

        return webClient.get()
                .uri("/posts")
                .retrieve()
                .bodyToFlux(Post.class)
                .collectList()
                .block();
    }

    // ─── GET a post by id ─────────────────────────────────────────────────────
    //
    // Annotation: @Cacheable("postById")  (from org.springframework.cache.annotation)
    //   PERFORMANCE: caches each post keyed by its id. The default key is the
    //   method argument (id), so each distinct id gets its own cache entry.
    //   A cache HIT for a given id returns instantly without any HTTP call.
    //
    //   key = "#id" is explicit here for clarity (it is also the default when
    //   there is a single argument).
    //
    // Same resilience combination as getAllPosts, with a different fallbackMethod.
    //
    // Annotation: @Retry  → retries the call if a network/timeout exception is thrown.
    // Annotation: @CircuitBreaker → opens the circuit after too many failed retry cycles.
    //
    // WebClient chain:
    //   .uri("/posts/{id}", id)  → URI template, WebClient URL-encodes the parameter
    //                              automatically (safe against path injection)
    //   .bodyToMono(Post.class)  → deserialize a single JSON object (not an array)
    //   .block()                 → block until the result arrives
    //
    // If the post does not exist (404) → WebClient throws WebClientResponseException.NotFound.
    // That exception propagates through @Retry (if listed) and is counted by @CircuitBreaker.
    @Cacheable(value = "postById", key = "#id")
    @Retry(name = CB_NAME)
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fallbackPostById")
    public Post getPostById(Integer id) {
        log.info("Calling external API: GET /posts/{}  (cache MISS — hitting the network)", id);

        return webClient.get()
                .uri("/posts/{id}", id)
                .retrieve()
                .bodyToMono(Post.class)
                .block();
    }

    // ─── FALLBACK METHODS ─────────────────────────────────────────────────────
    //
    // A fallback method is activated when:
    //   1. The circuit is OPEN (@CircuitBreaker skips the real call entirely)
    //   2. @Retry exhausted all attempts and the exception still propagates
    //
    // Naming rule: the fallbackMethod value in @CircuitBreaker must exactly match
    // the method name below.
    //
    // Signature rule: the fallback must have the SAME parameters as the original method
    // plus a Throwable as the last parameter. Spring uses this signature to match them.
    //
    //   Original:  public List<Post> getAllPosts()
    //   Fallback:  public List<Post> fallbackAllPosts(Throwable ex)         ✅
    //
    //   Original:  public Post getPostById(Integer id)
    //   Fallback:  public Post fallbackPostById(Integer id, Throwable ex)   ✅
    //
    // The fallback response is what the HTTP client receives instead of a 500 error.
    // This pattern is called "graceful degradation".

    // Fallback for getAllPosts — returns an empty list
    public List<Post> fallbackAllPosts(Throwable ex) {
        log.warn("Circuit breaker activated for getAllPosts. Reason: {}", ex.getMessage());
        // In production, consider returning cached data from Redis or a local DB
        return Collections.emptyList();
    }

    // Fallback for getPostById — returns a Post with placeholder values
    public Post fallbackPostById(Integer id, Throwable ex) {
        log.warn("Circuit breaker activated for getPostById({}). Reason: {}", id, ex.getMessage());
        // The client receives a degraded but valid response, not a 500 error
        return new Post(0, id, "Service temporarily unavailable", "Please try again later");
    }
}
