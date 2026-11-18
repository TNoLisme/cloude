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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionCommand;
import com.simulatedbank.banking.admin.application.ApprovalDecisionService.DecisionResult;
import com.simulatedbank.banking.admin.application.ApprovalProposalService;
import com.simulatedbank.banking.admin.application.ApprovalProposalService.LimitProposalCommand;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.LimitChangePayload;
import com.simulatedbank.banking.common.error.GlobalExceptionHandler;
import com.simulatedbank.banking.common.error.ProblemWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** BE-ADM-CON-03/INT-01: LIMIT proposal mapping and decision header/type mapping. */
class LimitAdminControllerTest {

    private static final String KEY = "idem-limit";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    private final ApprovalProposalService proposals = mock(ApprovalProposalService.class);
    private final ApprovalDecisionService decisions = mock(ApprovalDecisionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var writer = new ProblemWriter(Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new LimitAdminController(proposals, decisions))
                .setControllerAdvice(new GlobalExceptionHandler(writer))
                .build();
    }

    @Test
    void createMapsTheLimitProposalAndReturns201WithLocation() throws Exception {
        when(proposals.proposeLimit(any())).thenReturn(new com.simulatedbank.banking.admin.application
                .ApprovalProposalService.ProposalResult(201,
                Map.of("approvalId", "8001", "status", "PENDING", "requestType", "LIMIT_CHANGE"), false, 8001L));

        mvc.perform(post("/api/v1/admin/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("""
                                {"customerId":"2001","perTransactionLimit":"5000000.00",
                                 "dailyLimit":"20000000.00","currency":"VND"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/approvals/8001"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        ArgumentCaptor<LimitProposalCommand> captor = ArgumentCaptor.forClass(LimitProposalCommand.class);
        verify(proposals).proposeLimit(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo(2001L);
        assertThat(captor.getValue().perTransactionLimit()).isEqualByComparingTo("5000000.00");
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(KEY);
    }

    @Test
    void rejectDecisionUsesLimitTypeAndNoStore() throws Exception {
        ApprovalRequest pending = ApprovalRequest.propose(new LimitChangePayload(2001L, null,
                new BigDecimal("5000000.00"), new BigDecimal("20000000.00"), "VND", null, "limit"),
                1100L, "corr", NOW);
        when(decisions.decide(any())).thenReturn(new DecisionResult(pending, 200,
                Map.of("approvalId", "0", "status", "REJECTED"), true, ApprovalDecision.REJECT));

        mvc.perform(post("/api/v1/admin/limits/8001/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", KEY)
                        .content("""
                                {"decision":"REJECT","decisionNote":"denied"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        ArgumentCaptor<DecisionCommand> captor = ArgumentCaptor.forClass(DecisionCommand.class);
        verify(decisions).decide(captor.capture());
        assertThat(captor.getValue().expectedType()).isEqualTo(ApprovalRequestType.LIMIT_CHANGE);
        assertThat(captor.getValue().decision()).isEqualTo(ApprovalDecision.REJECT);
    }
}
