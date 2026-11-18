package com.simulatedbank.banking.admin.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.admin.application.AdminApprovalView;
import com.simulatedbank.banking.admin.application.ApprovalReadAuthorization;
import com.simulatedbank.banking.admin.application.port.ApprovalPayloadView;
import com.simulatedbank.banking.admin.application.query.ApprovalDetailProjection;
import com.simulatedbank.banking.admin.application.query.ApprovalPage;
import com.simulatedbank.banking.admin.application.query.ApprovalQueryService;
import com.simulatedbank.banking.admin.application.query.ApprovalSummaryProjection;
import com.simulatedbank.banking.admin.application.query.ApprovalVisibility;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.ApprovalTargetType;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.GlobalExceptionHandler;
import com.simulatedbank.banking.common.error.ProblemWriter;
import com.simulatedbank.banking.identity.api.IdentityUserQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** BE-ADM-API-03: HTTP shape, validation and anti-enumeration of the approval read endpoints. */
class ApprovalAdminControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final ApprovalVisibility VISIBILITY =
            new ApprovalVisibility(77L, java.util.Set.of(ApprovalRequestType.ROLE_CHANGE));
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApprovalQueryService queries = mock(ApprovalQueryService.class);
    private final ApprovalReadAuthorization authorization = mock(ApprovalReadAuthorization.class);
    private final ApprovalPayloadView payloads = mock(ApprovalPayloadView.class);
    private final IdentityUserQuery users = mock(IdentityUserQuery.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(authorization.requireVisibility()).thenReturn(VISIBILITY);
        when(users.findStaffDisplayName(anyLong())).thenReturn(Optional.of("Ops Staff"));
        var writer = new ProblemWriter(Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        var view = new AdminApprovalView(payloads, users);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ApprovalAdminController(queries, authorization, view, Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new GlobalExceptionHandler(writer))
                .build();
    }

    @Test
    void listReturnsPageResponseWithDocumentedSummaryFields() throws Exception {
        when(queries.list(any(), any())).thenReturn(new ApprovalPage(List.of(summary(8001L)), 0, 20, 1, 1));

        var result = mvc.perform(get("/api/v1/admin/approvals")
                        .queryParam("requestType", "ROLE_CHANGE")
                        .queryParam("status", "PENDING")
                        .queryParam("makerId", "1100")
                        .queryParam("page", "0")
                        .queryParam("size", "20")
                        .queryParam("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].approvalId").value("8001"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].maker.displayName").value("Ops Staff"))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(true))
                .andExpect(jsonPath("$.asOf").value("2026-11-18T10:00:00Z"))
                .andReturn();

        TreeSet<String> itemFields = new TreeSet<>();
        JSON.readTree(result.getResponse().getContentAsString()).get("items").get(0).fieldNames()
                .forEachRemaining(itemFields::add);
        assertThat(itemFields).containsExactly("appliedAt", "approvalId", "checker", "correlationId",
                "createdAt", "decidedAt", "decisionNote", "maker", "requestType", "status", "targetId",
                "targetType");
        // A list page never exposes the approval payload or provider version.
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("payload", "expectedVersion", "class ", "entity");
    }

    @Test
    void listRejectsInvalidEnumsAndPaginationBeforeTheService() throws Exception {
        // Page/size bounds are owned by the application service; map its rejection to the standard 400 too.
        when(queries.list(any(), any())).thenThrow(new BankingBusinessException(ErrorCode.VALIDATION_ERROR));
        for (String[] invalid : new String[][] {
                {"requestType", "ROLE_DEFINITION"}, {"status", "EXPIRED"}, {"page", "-1"}, {"page", "abc"},
                {"size", "0"}, {"size", "1000"}, {"makerId", "-5"}, {"makerId", "9999999999999999999"},
                {"from", "yesterday"}}) {
            mvc.perform(get("/api/v1/admin/approvals").queryParam(invalid[0], invalid[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void listMapsServiceValidationFailureToStandardProblem() throws Exception {
        when(queries.list(any(), any()))
                .thenThrow(new BankingBusinessException(ErrorCode.VALIDATION_ERROR));

        mvc.perform(get("/api/v1/admin/approvals").queryParam("sort", "status,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void listPassesParsedFiltersAndVisibilityToTheService() throws Exception {
        when(queries.list(any(), any())).thenReturn(new ApprovalPage(List.of(), 2, 5, 0, 0));

        mvc.perform(get("/api/v1/admin/approvals")
                        .queryParam("requestType", "ROLE_CHANGE")
                        .queryParam("status", "REJECTED")
                        .queryParam("makerId", "1100")
                        .queryParam("checkerId", "1101")
                        .queryParam("from", "2026-11-17T00:00:00Z")
                        .queryParam("to", "2026-11-18T00:00:00Z")
                        .queryParam("page", "2")
                        .queryParam("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.first").value(false))
                .andExpect(jsonPath("$.page.last").value(true));

        ArgumentCaptor<com.simulatedbank.banking.admin.application.query.ApprovalListQuery> query =
                ArgumentCaptor.forClass(com.simulatedbank.banking.admin.application.query.ApprovalListQuery.class);
        verify(queries).list(query.capture(), org.mockito.ArgumentMatchers.eq(VISIBILITY));
        assertThat(query.getValue().requestType()).isEqualTo(ApprovalRequestType.ROLE_CHANGE);
        assertThat(query.getValue().status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(query.getValue().makerId()).isEqualTo(1100L);
        assertThat(query.getValue().checkerId()).isEqualTo(1101L);
        assertThat(query.getValue().from()).isEqualTo(Instant.parse("2026-11-17T00:00:00Z"));
        assertThat(query.getValue().to()).isEqualTo(Instant.parse("2026-11-18T00:00:00Z"));
        assertThat(query.getValue().page()).isEqualTo(2);
        assertThat(query.getValue().size()).isEqualTo(5);
    }

    @Test
    void detailReturnsMaskedPayloadAndNoProviderVersion() throws Exception {
        when(queries.findDetail(8001L, VISIBILITY)).thenReturn(Optional.of(detail(8001L)));

        mvc.perform(get("/api/v1/admin/approvals/8001"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.approvalId").value("8001"))
                .andExpect(jsonPath("$.payload.operation").value("ASSIGN_ROLE"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void decisionNoteAndPayloadFreeTextAreRedactedInTheResponse() throws Exception {
        ApprovalSummaryProjection summary = new ApprovalSummaryProjection(8001L, ApprovalRequestType.ROLE_CHANGE,
                ApprovalTargetType.APP_USER, "1005", ApprovalStatus.REJECTED, 1100L, 1101L,
                NOW.minusSeconds(60), NOW, null, "token=abc123 password=hunter2", "adm-read-corr");
        when(queries.findDetail(8001L, VISIBILITY)).thenReturn(Optional.of(
                new ApprovalDetailProjection(summary, Map.of("reason", "otp=812345"))));

        mvc.perform(get("/api/v1/admin/approvals/8001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionNote").value("token=[REDACTED] password=[REDACTED]"))
                .andExpect(jsonPath("$.payload.reason").value("otp=[REDACTED]"));
    }

    @Test
    void unknownOrOutOfScopeDetailIsTheSame404() throws Exception {
        when(queries.findDetail(anyLong(), any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/admin/approvals/8001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPROVAL_NOT_FOUND"));
        mvc.perform(get("/api/v1/admin/approvals/9000001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPROVAL_NOT_FOUND"));
    }

    @Test
    void malformedDetailIdIs400WithoutCallingTheService() throws Exception {
        mvc.perform(get("/api/v1/admin/approvals/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(queries);
    }

    @Test
    void pageResolvesEachDistinctActorOnce() throws Exception {
        when(queries.list(any(), any())).thenReturn(new ApprovalPage(
                List.of(summary(8001L), summary(8002L)), 0, 20, 2, 1));

        mvc.perform(get("/api/v1/admin/approvals")).andExpect(status().isOk());

        org.mockito.Mockito.verify(users).findStaffDisplayName(1100L);
        org.mockito.Mockito.verifyNoMoreInteractions(users);
    }

    private static ApprovalSummaryProjection summary(long approvalId) {
        return new ApprovalSummaryProjection(approvalId, ApprovalRequestType.ROLE_CHANGE,
                ApprovalTargetType.APP_USER, "1005", ApprovalStatus.PENDING, 1100L, null,
                NOW.minusSeconds(60), null, null, null, "adm-read-corr");
    }

    private static ApprovalDetailProjection detail(long approvalId) {
        return new ApprovalDetailProjection(summary(approvalId), Map.of("operation", "ASSIGN_ROLE"));
    }
}
