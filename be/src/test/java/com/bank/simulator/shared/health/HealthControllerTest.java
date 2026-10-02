package com.bank.simulator.shared.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({HealthControllerTest.ClockConfigurationTestConfig.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DatabaseHealthIndicator databaseHealthIndicator;

    @Test
    void returnsExactHealthSchemaWhenDatabaseIsAvailable() throws Exception {
        when(databaseHealthIndicator.isAvailable()).thenReturn(true);
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\",\"timestamp\":\"2026-01-01T00:00:00Z\"}", true));
    }

    @Test
    void returnsServiceUnavailableWhenDatabaseIsDown() throws Exception {
        when(databaseHealthIndicator.isAvailable()).thenReturn(false);
        mockMvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.timestamp").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.database").doesNotExist());
    }

    @TestConfiguration
    static class ClockConfigurationTestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
