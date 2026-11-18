package com.simulatedbank.banking.admin.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.ProposalResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.RoleProposalCommand;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.GlobalExceptionHandler;
import com.simulatedbank.banking.common.error.ProblemWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** BE-ADM-CON-03: inbound contract for headers, replay header, Location and 428 challenge mapping. */
class RoleAdminControllerTest {

    private static final String KEY = "idem-key";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    private final ApprovalProposalService proposals = mock(ApprovalProposalService.class);
    private final ApprovalDecisionService decisions = mock(ApprovalDecisionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var writer = new ProblemWriter(Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        // Match application.yml: an unknown decision field is a 400, never a silently ignored value.
        var mapper = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var json = new MappingJackson2HttpMessageConverter(mapper);
        json.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mvc = MockMvcBuilders.standaloneSetup(new RoleAdminController(proposals, decisions))
                .setControllerAdvice(new GlobalExceptionHandler(writer))
                .setMessageConverters(json)
                .build();
    }

    @Test
    void createReturns201LocationAndNoStore() throws Exception {
        when(proposals.proposeRole(any())).thenReturn(new ProposalResult(201,
                Map.of("approvalId", "8001", "status", "PENDING"), false, 8001L));

        mvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .header("X-Step-Up-Challenge", "challenge-ref")
                        .content("""
                                {"operation":"ASSIGN_ROLE","targetUserId":"1005","roleCode":"OPERATIONS",
                                 "reason":"reason"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/approvals/8001"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.approvalId").value("8001"));

        ArgumentCaptor<RoleProposalCommand> captor = ArgumentCaptor.forClass(RoleProposalCommand.class);
        verify(proposals).proposeRole(captor.capture());
        assertThat(captor.getValue().operation()).isEqualTo(RoleChangeOperation.ASSIGN_ROLE);
        assertThat(captor.getValue().targetUserId()).isEqualTo(1005L);
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(KEY);
        assertThat(captor.getValue().challengeRef()).isEqualTo("challenge-ref");
    }

    @Test
    void replayAddsIdempotentReplayedHeader() throws Exception {
        when(proposals.proposeRole(any())).thenReturn(new ProposalResult(201,
                Map.of("approvalId", "8001", "status", "PENDING"), true, 8001L));

        mvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("""
                                {"operation":"ASSIGN_ROLE","targetUserId":"1005","roleCode":"OPERATIONS"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void stepUpRequiredIsMappedWithPublicChallenge() throws Exception {
        UUID challengeId = UUID.randomUUID();
        Map<String, Object> challenge = Map.of(
                "challengeId", challengeId.toString(),
                "purpose", "SENSITIVE_ACTION",
                "methods", List.of("OTP"),
                "expiresAt", NOW.plusSeconds(300).toString(),
                "maxAttempts", 5,
                "remainingAttempts", 5);
        when(proposals.proposeRole(any())).thenThrow(new BankingBusinessException(ErrorCode.STEP_UP_REQUIRED,
                ErrorCode.STEP_UP_REQUIRED.detail(), Map.of("challenge", challenge)));

        mvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("""
                                {"operation":"ASSIGN_ROLE","targetUserId":"1005","roleCode":"OPERATIONS"}
                                """))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"))
                .andExpect(jsonPath("$.challenge.challengeId").value(challengeId.toString()));
    }

    @Test
    void missingKeyIs400WithHeaderField() throws Exception {
        when(proposals.proposeRole(any())).thenThrow(new com.simulatedbank.banking.common.error
                .BankingValidationException("Idempotency-Key", "Idempotency-Key là bắt buộc."));

        mvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operation":"ASSIGN_ROLE","targetUserId":"1005","roleCode":"OPERATIONS"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void targetUserIdOverBigintIs400WithoutCallingTheService() throws Exception {
        mvc.perform(post("/api/v1/admin/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("""
                                {"operation":"ASSIGN_ROLE","targetUserId":"9999999999999999999",
                                 "roleCode":"OPERATIONS"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(proposals, org.mockito.Mockito.never()).proposeRole(any());
    }

    @Test
    void invalidDecisionIs400BeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/admin/roles/8001/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("""
                                {"decision":"MAYBE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void clientSuppliedExpectedVersionIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/admin/roles/8001/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("""
                                {"decision":"APPROVE","expectedVersion":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verify(decisions, org.mockito.Mockito.never()).decide(any());
    }

    @Test
    void decisionReturns200AndNoStore() throws Exception {
        ApprovalRequest pending = ApprovalRequest.propose(new RoleChangePayload(
                RoleChangeOperation.ASSIGN_ROLE, 1005L, "OPERATIONS", null, 0L, "reason"),
                1100L, "corr", NOW);
        when(decisions.decide(any())).thenReturn(new DecisionResult(pending, 200,
                Map.of("approvalId", "0", "status", "APPLIED"), false, ApprovalDecision.APPROVE));

        mvc.perform(post("/api/v1/admin/roles/8001/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("""
                                {"decision":"APPROVE","decisionNote":"ok"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("APPLIED"));

        ArgumentCaptor<DecisionCommand> captor = ArgumentCaptor.forClass(DecisionCommand.class);
        verify(decisions).decide(captor.capture());
        assertThat(captor.getValue().approvalId()).isEqualTo(8001L);
        assertThat(captor.getValue().expectedType()).isEqualTo(ApprovalRequestType.ROLE_CHANGE);
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(KEY);
    }
}
