package com.simulatedbank.banking.common.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational API only; never exposes contributor details or fabricates business health. */
@RestController
public class HealthController {
    private final HealthEndpoint health;
    private final ApplicationAvailability availability;
    private final Clock clock;
    private final String version;

    public HealthController(HealthEndpoint health, ApplicationAvailability availability, Clock clock,
            @Value("${info.app.version:dev}") String version) {
        this.health = health;
        this.availability = availability;
        this.clock = clock;
        this.version = version;
    }

    @GetMapping("/api/v1/health")
    public ResponseEntity<HealthResponse> health() {
        String database = status(health.healthForPath("db"));
        String application = availability.getLivenessState() == LivenessState.CORRECT ? "UP" : "DOWN";
        String aggregate = status(health.health());
        String status = "DOWN".equals(database) || "DOWN".equals(application) ? "DOWN" : aggregate;
        return ResponseEntity.status("DOWN".equals(status) ? 503 : 200)
                .body(new HealthResponse(status, clock.instant(), version,
                        Map.of("database", Map.of("status", database), "application", Map.of("status", application))));
    }

    private static String status(HealthComponent health) {
        if (health == null) return "DOWN";
        return switch (health.getStatus().getCode()) {
            case "UP" -> "UP";
            case "DEGRADED" -> "DEGRADED";
            default -> "DOWN";
        };
    }

    public record HealthResponse(String status, Instant checkedAt, String version,
            Map<String, Map<String, String>> components) {}
}
