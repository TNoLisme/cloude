package com.bank.simulator.shared.health;

import java.time.Clock;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final DatabaseHealthIndicator databaseHealthIndicator;
    private final Clock clock;

    public HealthController(DatabaseHealthIndicator databaseHealthIndicator, Clock clock) {
        this.databaseHealthIndicator = databaseHealthIndicator;
        this.clock = clock;
    }

    @GetMapping("/health")
    public ResponseEntity<Health> health() {
        if (databaseHealthIndicator.isAvailable()) {
            return ResponseEntity.ok(new Health("UP", Instant.now(clock)));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Health("DOWN", Instant.now(clock)));
    }

    public record Health(String status, Instant timestamp) {
    }
}
