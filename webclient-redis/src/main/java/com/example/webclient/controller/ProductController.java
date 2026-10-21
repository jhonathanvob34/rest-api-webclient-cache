package com.example.webclient.controller;

import com.example.webclient.model.Post;
import com.example.webclient.model.ProductRequest;
import com.example.webclient.model.ProductResponse;
import com.example.webclient.service.ExternalApiService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// ─────────────────────────────────────────────────────────────────────────────
// ProductController — exposes OUR REST API to external callers.
//
// ANNOTATIONS USED IN THIS CLASS:
//
//   From Spring Web (org.springframework.web.bind.annotation):
//     @RestController  → marks this class as a REST controller.
//                        Combines @Controller + @ResponseBody: every method
//                        return value is serialized to JSON automatically.
//     @RequestMapping  → sets the base path for all endpoints in this class
//     @PostMapping     → handles HTTP POST requests
//     @GetMapping      → handles HTTP GET requests
//     @RequestBody     → deserializes the JSON request body into a Java object
//     @PathVariable    → binds a URI template variable {id} to a method parameter
//
//   From Jakarta Validation (jakarta.validation):
//     @Valid           → triggers validation of the annotated parameter.
//                        The constraints come from the DTO itself (@NotBlank, @Min, etc.)
//                        If validation fails → Spring returns 400 Bad Request automatically.
//
// ─────────────────────────────────────────────────────────────────────────────
// TWO RESPONSIBILITIES IN THIS CONTROLLER:
//
//   1. /products  → this app IS the server.
//                   External clients (Postman, frontend, microservice) call US.
//
//   2. /posts     → this app IS the client.
//                   We call jsonplaceholder.typicode.com via WebClient.
//                   The Retry + Circuit Breaker logic lives in ExternalApiService.
//
// The controller only handles HTTP concerns (status codes, request/response mapping).
// Business logic and external calls belong in the service layer.
// ─────────────────────────────────────────────────────────────────────────────
@RestController
@RequestMapping("/api/v1")
public class ProductController {

    private final ExternalApiService externalApiService;

    // Constructor injection — no @Autowired needed (Spring injects automatically when
    // there is only one constructor). Same pattern used in the v8 project.
    public ProductController(ExternalApiService externalApiService) {
        this.externalApiService = externalApiService;
    }

    // ─── OUR API: expose products ─────────────────────────────────────────────

    // POST /api/v1/products
    // Receives a product from the caller, validates it, and returns the created entity.
    // @Valid activates the jakarta.validation constraints declared on ProductRequest fields.
    // Validation failure → Spring returns 400 Bad Request automatically (no extra code needed).
    @PostMapping("/products")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        ProductResponse response = ProductResponse.from(request);
        // 201 Created — HTTP standard for a newly created resource
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // GET /api/v1/products/ping
    // Simple liveness check. Useful for manual health checks and Postman smoke tests.
    @GetMapping("/products/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Product service is UP");
    }

    // ─── CONSUME EXTERNAL API: posts from jsonplaceholder ─────────────────────

    // GET /api/v1/posts
    // Calls jsonplaceholder.typicode.com/posts via WebClient.
    // @Retry + @CircuitBreaker are configured on ExternalApiService, not here.
    // The controller just delegates and maps the result to an HTTP response.
    @GetMapping("/posts")
    public ResponseEntity<List<Post>> getAllPosts() {
        List<Post> posts = externalApiService.getAllPosts();
        return ResponseEntity.ok(posts);
    }

    // GET /api/v1/posts/{id}
    // @PathVariable binds the {id} segment from the URL to the Integer id parameter.
    // If the circuit breaker is OPEN → ExternalApiService returns the fallback response
    // and this method returns 200 with that fallback data (no exception reaches here).
    @GetMapping("/posts/{id}")
    public ResponseEntity<Post> getPostById(@PathVariable Integer id) {
        Post post = externalApiService.getPostById(id);
        return ResponseEntity.ok(post);
    }
}
