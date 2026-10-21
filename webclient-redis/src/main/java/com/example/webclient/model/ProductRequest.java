package com.example.webclient.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// ─────────────────────────────────────────────────────────────────────────────
// ProductRequest — DTO received when an external caller hits OUR API.
//
// ANNOTATIONS USED IN THIS CLASS:
//
//   From Jakarta Validation (jakarta.validation.constraints):
//     @NotBlank  → field must not be null, empty, or whitespace-only
//     @NotNull   → field must not be null (use with @Min for numeric types)
//     @Min       → field value must be >= the specified minimum
//
//   These annotations do nothing on their own. They are activated by placing
//   @Valid on the @RequestBody parameter in the controller method.
//   If any constraint fails → Spring automatically returns 400 Bad Request
//   with a body describing which fields failed and why.
// ─────────────────────────────────────────────────────────────────────────────
public record ProductRequest(

        @NotBlank(message = "name must not be blank")
        String name,

        @NotBlank(message = "category must not be blank")
        String category,

        @NotNull(message = "price is required")
        @Min(value = 1, message = "price must be greater than 0")
        Double price,

        @NotNull(message = "stock is required")
        @Min(value = 0, message = "stock must not be negative")
        Integer stock
) {}
