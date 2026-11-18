package com.simulatedbank.banking.admin.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.LimitProposalCommand;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.GlobalExceptionHandler;
import com.simulatedbank.banking.common.error.ProblemWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * BE-ADM-TST-01 §3.2: request-shape validation is enforced before the application service runs.
 *
 * <p>The project deliberately uses manual validation in {@link AdminHttpSupport}/domain instead of
 * {@code @Valid}, so this suite asserts the actual HTTP behaviour (status, Problem code, no service call) for
 * missing fields, malformed enums/IDs/money/instants, unknown fields and malformed JSON.</p>
 */
class AdminDtoValidationTest {

    private static final String KEY = "idem-validation";
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    private final ApprovalProposalService proposals = mock(ApprovalProposalService.class);
    private final ApprovalDecisionService decisions = mock(ApprovalDecisionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var writer = new ProblemWriter(Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        var mapper = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var json = new MappingJackson2HttpMessageConverter(mapper);
        json.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mvc = MockMvcBuilders.standaloneSetup(
                        new RoleAdminController(proposals, decisions),
                        new LimitAdminController(proposals, decisions))
                .setControllerAdvice(new GlobalExceptionHandler(writer))
                .setMessageConverters(json)
                .build();
    }

    @Test
    void roleProposalRejectsMissingInvalidUnknownAndMalformedFieldsBeforeTheService() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"operation\":\"ASSIGN\"}",
                "{\"operation\":\"ASSIGN_ROLE\"}",
                "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"0\",\"roleCode\":\"AUDITOR\"}",
                "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"-1\",\"roleCode\":\"AUDITOR\"}",
                "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"9999999999999999999\","
                        + "\"roleCode\":\"AUDITOR\"}")) {
            mvc.perform(post("/api/v1/admin/roles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", KEY)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        for (String body : List.of(
                "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"1005\",\"roleCode\":\"AUDITOR\","
                        + "\"makerId\":\"1006\"}",
                "{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"1005\",\"roleCode\":\"AUDITOR\","
                        + "\"status\":\"APPLIED\"}")) {
            mvc.perform(post("/api/v1/admin/roles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", KEY)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        mvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("{\"operation\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(proposals);
    }

    @Test
    void limitProposalRejectsMissingAndMalformedFieldsBeforeTheService() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"customerId\":\"0\"}",
                "{\"customerId\":\"2001\",\"perTransactionLimit\":\"abc\",\"dailyLimit\":\"20000000.00\"}",
                "{\"customerId\":\"2001\",\"perTransactionLimit\":\"5000000.00\",\"dailyLimit\":\"abc\"}",
                "{\"customerId\":\"2001\",\"accountId\":\"9999999999999999999\","
                        + "\"perTransactionLimit\":\"5000000.00\",\"dailyLimit\":\"20000000.00\"}",
                "{\"customerId\":\"2001\",\"perTransactionLimit\":\"5000000.00\","
                        + "\"dailyLimit\":\"20000000.00\",\"effectiveFrom\":\"yesterday\"}")) {
            mvc.perform(post("/api/v1/admin/limits")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", KEY)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(post("/api/v1/admin/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("{\"customerId\":\"2001\",\"perTransactionLimit\":\"5000000.00\","
                                + "\"dailyLimit\":\"20000000.00\",\"expectedVersion\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(proposals);
    }

    @Test
    void limitMoneyBoundaryReachesTheDomainWhichRejectsNonPositiveValues() throws Exception {
        when(proposals.proposeLimit(any()))
                .thenThrow(new BankingBusinessException(ErrorCode.VALIDATION_ERROR));

        mvc.perform(post("/api/v1/admin/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("{\"customerId\":\"2001\",\"perTransactionLimit\":\"0.00\","
                                + "\"dailyLimit\":\"20000000.00\",\"currency\":\"VND\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        ArgumentCaptor<LimitProposalCommand> command = ArgumentCaptor.forClass(LimitProposalCommand.class);
        verify(proposals).proposeLimit(command.capture());
        assertThat(command.getValue().perTransactionLimit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(command.getValue().currency()).isEqualTo("VND");
    }

    @Test
    void decisionRejectsMissingInvalidUnknownAndMalformedFieldsBeforeTheService() throws Exception {
        for (String body : List.of("{}", "{\"decision\":\"MAYBE\"}")) {
            mvc.perform(post("/api/v1/admin/roles/8001/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", KEY)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        for (String body : List.of(
                "{\"decision\":\"APPROVE\",\"checkerId\":\"1101\"}",
                "{\"decision\":\"APPROVE\",\"expectedVersion\":1}")) {
            mvc.perform(post("/api/v1/admin/roles/8001/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", KEY)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        mvc.perform(post("/api/v1/admin/roles/abc/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/admin/roles/9999999999999999999/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(decisions, never()).decide(any());
    }

    @Test
    void unknownOptionalFieldsAreNotAcceptedEvenWhenTheyLookLikeServerState() throws Exception {
        mvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("{\"operation\":\"ASSIGN_ROLE\",\"targetUserId\":\"1005\","
                                + "\"roleCode\":\"AUDITOR\",\"status\":\"APPLIED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verify(proposals, never()).proposeRole(any());
    }

    @Test
    void jsonNullBodyIsRejectedAsMalformedRequest() throws Exception {
        mvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verify(proposals, never()).proposeRole(any());
    }
}
