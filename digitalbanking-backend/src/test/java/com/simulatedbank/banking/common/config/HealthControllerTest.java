package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;

class HealthControllerTest {
    private final HealthEndpoint endpoint = mock(HealthEndpoint.class);
    private final ApplicationAvailability availability = mock(ApplicationAvailability.class);
    private final HealthController controller = new HealthController(endpoint, availability,
            Clock.fixed(Instant.parse("2026-11-12T11:00:00Z"), ZoneOffset.UTC), "test-version");

    @Test
    void missingDatabaseCannotClaimHealthyEvenIfOtherContributorsAreUp() {
        when(endpoint.health()).thenReturn(Health.up().build());
        when(availability.getLivenessState()).thenReturn(LivenessState.CORRECT);
        assertThat(controller.health().getStatusCode().value()).isEqualTo(503);
        assertThat(controller.health().getBody().components().get("database").get("status")).isEqualTo("DOWN");
    }

    @Test
    void degradedIs200AndUnsafeContributorDetailsAreDiscarded() {
        when(endpoint.healthForPath("db")).thenReturn(Health.up().withDetail("password", "SYNTHETIC").build());
        when(endpoint.health()).thenReturn(Health.status(new Status("DEGRADED")).build());
        when(availability.getLivenessState()).thenReturn(LivenessState.CORRECT);
        var response = controller.health();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().status()).isEqualTo("DEGRADED");
        assertThat(response.getBody().checkedAt()).isEqualTo(Instant.parse("2026-11-12T11:00:00Z"));
        assertThat(response.getBody().toString()).doesNotContain("password", "SYNTHETIC");
    }

    @Test
    void brokenLivenessOverridesDatabaseUp() {
        when(endpoint.healthForPath("db")).thenReturn(Health.up().build());
        when(endpoint.health()).thenReturn(Health.up().build());
        when(availability.getLivenessState()).thenReturn(LivenessState.BROKEN);
        assertThat(controller.health().getStatusCode().value()).isEqualTo(503);
    }
}
