package com.example.webclient.model;

// ─────────────────────────────────────────────────────────────────────────────
// Post — represents a post returned by the external jsonplaceholder API.
//
// Uses a Java Record (Java 14+), the same pattern as in the v8 project.
// A record is immutable, auto-generates equals/hashCode/toString,
// and is ideal for DTOs coming from external APIs.
//
// The external API returns this JSON shape:
//   { "userId": 1, "id": 1, "title": "...", "body": "..." }
// ─────────────────────────────────────────────────────────────────────────────
public record Post(
        Integer userId,
        Integer id,
        String title,
        String body
) {}
