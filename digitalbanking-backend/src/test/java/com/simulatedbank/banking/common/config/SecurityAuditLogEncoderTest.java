package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

class SecurityAuditLogEncoderTest {
    @Test
    void securityGapCarriesSafeStructuredAuditFieldsWithoutRawMessage() throws Exception {
        var event = mock(ILoggingEvent.class);
        when(event.getLevel()).thenReturn(Level.WARN);
        when(event.getLoggerName()).thenReturn("com.simulatedbank.banking.common.config.SecurityAuditGapLogger");
        when(event.getMessage()).thenReturn("SECURITY_AUDIT_GAP");
        when(event.getMDCPropertyMap()).thenReturn(Map.of("correlationId", "request-1"));
        when(event.getKeyValuePairs()).thenReturn(List.of(
                new KeyValuePair("action", "SECURITY_ACCESS_DENIED"),
                new KeyValuePair("outcome", "REJECTED"),
                new KeyValuePair("user_id", "sha256:" + "a".repeat(64)),
                new KeyValuePair("password", "secret")));
        var json = new ObjectMapper().readTree(new String(new SafeJsonLogEncoder().encode(event), StandardCharsets.UTF_8));
        assertThat(json.path("event").asText()).isEqualTo("SECURITY_AUDIT_GAP");
        assertThat(json.path("action").asText()).isEqualTo("SECURITY_ACCESS_DENIED");
        assertThat(json.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(json.path("correlation_id").asText()).isEqualTo("request-1");
        assertThat(json.path("user_id").asText()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(json.has("password")).isFalse();
    }
}
