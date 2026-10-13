package com.dhir.spring_boot;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backend liveness only: "the Spring Boot process is up and answering HTTP".
 * It does NOT check whether any model worker is reachable or ready.
 *
 * The response is always text/plain, even if the client sends Accept: application/json,
 * so the content type never claims JSON while the body is plain text.
 */
@RestController
public class HealthController {

    static final String BODY = "Backend is healthy";

    @GetMapping("/api/health")
    public ResponseEntity<String> health() {
        // An explicitly set Content-Type is used as-is by Spring (no content negotiation).
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(BODY);
    }
}
