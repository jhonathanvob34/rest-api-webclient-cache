package com.example.webclient.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

// ─────────────────────────────────────────────────────────────────────────────
// WebClientConfig — configures the WebClient bean with explicit timeouts.
//
// ANNOTATIONS USED IN THIS CLASS:
//
//   From Spring (org.springframework.context.annotation):
//     @Configuration → marks this class as a source of Spring bean definitions
//     @Bean          → marks webClient() as a bean to be managed by Spring
//
//   From Spring (org.springframework.beans.factory.annotation):
//     @Value         → injects a value from application.yml into a field
//
// ─────────────────────────────────────────────────────────────────────────────
// WHY CONFIGURE TIMEOUTS?
//
//   Without timeouts, if the external API hangs, your thread waits forever.
//   This exhausts the thread pool and takes down your entire application
//   (known as a "cascading failure").
//
//   Three timeout layers configured here:
//     connectionTimeout → max time to establish the TCP connection     (2s)
//     responseTimeout   → max time waiting for the first byte back     (5s)
//     readTimeout       → max time reading the full response body      (5s)
//
//   Relationship with Resilience4j:
//     A timeout exception triggers @Retry → @Retry retries the call.
//     If all retries time out → @CircuitBreaker counts it as one failure.
// ─────────────────────────────────────────────────────────────────────────────
@Configuration
public class WebClientConfig {

    // Reads externalApi.base-url from application.yml
    // The ${...} syntax means: read this property; no default → fails fast at startup if missing
    @Value("${externalApi.base-url}")
    private String baseUrl;

    @Bean
    public WebClient webClient() {

        // Reactor Netty HttpClient — the underlying HTTP engine for WebClient
        HttpClient httpClient = HttpClient.create()
                // Max time to open the TCP connection to the external server
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
                // Max time waiting for the server to start sending a response
                .responseTimeout(Duration.ofSeconds(5))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS))   // max time reading the body
                            .addHandlerLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS))  // max time sending the request
                );

        return WebClient.builder()
                .baseUrl(baseUrl)                          // e.g. https://jsonplaceholder.typicode.com
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
