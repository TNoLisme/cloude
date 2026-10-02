package com.bank.simulator.shared.error;

import com.bank.simulator.shared.api.Problem;
import com.bank.simulator.shared.correlation.CorrelationIdFilter;
import com.bank.simulator.shared.health.DatabaseHealthIndicator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, GlobalExceptionHandlerTest.TestController.class,
        GlobalExceptionHandlerTest.TestBeans.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DatabaseHealthIndicator databaseHealthIndicator;

    @Test
    void mapsApiExceptionToProblemDetails() throws Exception {
        UUID correlationId = UUID.randomUUID();
        mockMvc.perform(get("/test/business-error").header(CorrelationIdFilter.HEADER_NAME, correlationId))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(result -> {
                    Problem problem = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(result.getResponse().getContentAsString(), Problem.class);
                    assertThat(problem.code()).isEqualTo("TEST_CONFLICT");
                    assertThat(problem.correlationId()).isEqualTo(correlationId);
                    assertThat(problem.instance()).isEqualTo("/test/business-error");
                });
    }

    @Test
    void sanitizesUnexpectedException() throws Exception {
        mockMvc.perform(get("/test/unexpected-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(result -> {
                    Problem problem = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(result.getResponse().getContentAsString(), Problem.class);
                    assertThat(problem.code()).isEqualTo(ProblemCode.INTERNAL_ERROR);
                    assertThat(problem.detail()).isEqualTo("An unexpected error occurred.");
                    assertThat(problem.detail()).doesNotContain("sensitive");
                });
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/test/business-error")
        void businessError() {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "TEST_CONFLICT", "Conflict.");
        }

        @GetMapping("/test/unexpected-error")
        void unexpectedError() {
            throw new IllegalStateException("sensitive internal detail");
        }
    }
}
