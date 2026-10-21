package com.example.webclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

// @EnableCaching activates Spring's cache abstraction. Without it, the
// @Cacheable annotations in ExternalApiService are ignored.
//
// The cache provider is Caffeine (configured in application.yml under
// spring.cache). To switch to Redis in production, see the notes in pom.xml
// and application.yml — the application code stays exactly the same.
@EnableCaching
@SpringBootApplication
public class RestApiWebClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestApiWebClientApplication.class, args);
    }
}
