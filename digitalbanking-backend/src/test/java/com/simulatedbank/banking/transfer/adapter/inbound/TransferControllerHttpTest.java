package com.simulatedbank.banking.transfer.adapter.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.transfer.application.AccountOwnershipPort;
import com.simulatedbank.banking.transfer.application.AccountOwnershipPort.Ownership;
import com.simulatedbank.banking.transfer.application.AuditEvent;
import com.simulatedbank.banking.transfer.application.AuditEventPort;
import com.simulatedbank.banking.transfer.application.FraudFlagReadPort;
import com.simulatedbank.banking.transfer.application.FraudFlagView;
import com.simulatedbank.banking.transfer.application.MaskedAccount;
import com.simulatedbank.banking.transfer.application.StepUpChallenge;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.AccountInactive;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.AccountNotFound;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Completed;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Conflict;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Failed;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.InProgress;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.InsufficientBalance;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Replay;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.SourceNotOwned;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.StepUpRequired;
import com.simulatedbank.banking.transfer.application.TransferFailureCode;
import com.simulatedbank.banking.transfer.application.TransferOrchestrator;
import com.simulatedbank.banking.transfer.application.TransferReadPort;
import com.simulatedbank.banking.transfer.application.TransferView;
import com.simulatedbank.banking.transfer.domain.TransferAmount;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import com.simulatedbank.transfercandidate.web.TransferHttpConfiguration;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.identity.enabled=false",
        "app.customer.enabled=false",
        "app.account.enabled=false",
        "app.admin.enabled=false",
        "app.audit.enabled=false",
        "app.limit.enabled=false",
        "app.fraud.enabled=false",
        "app.notification.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TransferHttpConfiguration.class, TransferControllerHttpTest.Fakes.class})
class TransferControllerHttpTest {
    private static final Instant NOW = Instant.parse("2026-11-16T10:15:30Z");
    private static final String REF = "TXN20261116A1B2C3D4";
    private static final String VALID = """
            {"sourceAccountId":"3001","destination":{"accountNumber":"0123456789"},
             "amount":"150000.00","description":"Chuyen tien"}
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Fakes fakes;

    @BeforeEach
    void reset() {
        fakes.outcome.set(new Completed(view(TransferStatus.COMPLETED, null)));
        fakes.stored.set(Optional.of(view(TransferStatus.COMPLETED, null)));
        fakes.events.clear();
        fakes.orchestratorCalls.set(0);
    }

    @Test
    void createCompletedReturns201LocationAndContractSchema() throws Exception {
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                        .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/transfers/" + REF))
                .andExpect(header().string("X-Correlation-ID", org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.reference").value(REF))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value("150000.00"))
                .andExpect(jsonPath("$.currency").value("VND"))
                .andExpect(jsonPath("$.sourceAccount.accountNumberMasked").value("**** **** 3001"))
                .andExpect(jsonPath("$.sourceAccount.accountNumberLast4").value("3001"))
                .andExpect(jsonPath("$.destinationAccount.accountId").value("3002"))
                .andExpect(jsonPath("$.correlationId").isString())
                .andExpect(jsonPath("$.createdAt").value("2026-11-16T10:15:30Z"))
                .andExpect(jsonPath("$.completedAt").value("2026-11-16T10:15:30Z"));
    }

    @Test
    void missingIdempotencyKeyIs400WithHeaderFieldAndCreatesNothing() throws Exception {
        var result = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        problem(result, 400, "VALIDATION_ERROR");
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errors").get(0).path("field").asText())
                .isEqualTo("Idempotency-Key");
        assertThat(fakes.orchestratorCalls).hasValue(0);
    }

    @Test
    void invalidAmountIs400WithRelativeFieldPath() throws Exception {
        var result = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("150000.00", "0.00"))).andReturn();
        problem(result, 400, "VALIDATION_ERROR");
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("errors").get(0).path("field").asText())
                .isEqualTo("amount");
    }

    @Test
    void malformedJsonIs400MalformedAndOversizeIs413() throws Exception {
        problem(mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content("{not-json")).andReturn(),
                400, "MALFORMED_REQUEST");
        problem(mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content("x".repeat(65537))).andReturn(),
                413, "REQUEST_TOO_LARGE");
    }

    @Test
    void wrongMediaTypeIs415() throws Exception {
        problem(mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.TEXT_PLAIN).content(VALID)).andReturn(),
                415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void terminalReplayKeepsOriginalBodyAndStatusWithReplayedHeaderAndCurrentCorrelation() throws Exception {
        fakes.outcome.set(new Replay(201, mapper.writeValueAsString(responseJson())));
        var result = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").header("X-Correlation-ID", "retry-correlation")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(result.getResponse().getHeader("X-Correlation-ID")).isEqualTo("retry-correlation");
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("correlationId").asText())
                .isEqualTo("corr-original");
    }

    @Test
    void conflictIs409WithSecurityAuditAndInProgressIs409WithRetryAfter() throws Exception {
        fakes.outcome.set(new Conflict());
        var conflict = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        problem(conflict, 409, "IDEMPOTENCY_CONFLICT");
        assertThat(fakes.events).anySatisfy(event -> assertThat(event.action()).isEqualTo("SECURITY_IDEMPOTENCY_CONFLICT"));
        fakes.outcome.set(new InProgress());
        var inProgress = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        problem(inProgress, 409, "IDEMPOTENCY_IN_PROGRESS");
        assertThat(inProgress.getResponse().getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void stepUpIs428WithChallengeAndCreatesNothing() throws Exception {
        fakes.outcome.set(new StepUpRequired(new StepUpChallenge(UUID.randomUUID(), "TRANSFER_STEP_UP",
                List.of("OTP"), "SMS", "********123", NOW.plusSeconds(300), 5, 5)));
        var result = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        problem(result, 428, "STEP_UP_REQUIRED");
        var challenge = mapper.readTree(result.getResponse().getContentAsString()).path("challenge");
        assertThat(challenge.path("purpose").asText()).isEqualTo("TRANSFER_STEP_UP");
        assertThat(challenge.path("methods").get(0).asText()).isEqualTo("OTP");
        assertThat(challenge.path("maxAttempts").asInt()).isEqualTo(5);
    }

    @Test
    void postT1FailedIs422WithReferenceStatusAndFailureCode() throws Exception {
        fakes.outcome.set(new Failed(view(TransferStatus.FAILED, TransferFailureCode.INSUFFICIENT_BALANCE)));
        var result = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        problem(result, 422, "INSUFFICIENT_BALANCE");
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("reference").asText()).isEqualTo(REF);
        assertThat(body.path("transferStatus").asText()).isEqualTo("FAILED");
        assertThat(body.path("failureCode").asText()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void postT1AccountInactiveMapsToTransferFailedWithFailureCode() throws Exception {
        fakes.outcome.set(new Failed(view(TransferStatus.FAILED, TransferFailureCode.ACCOUNT_INACTIVE)));
        var result = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        problem(result, 422, "TRANSFER_FAILED");
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("failureCode").asText())
                .isEqualTo("ACCOUNT_INACTIVE");
    }

    @Test
    void preT1AccountErrorsCarryNoReferenceOrTransferStatus() throws Exception {
        fakes.outcome.set(new AccountNotFound());
        var notFound = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        problem(notFound, 404, "ACCOUNT_NOT_FOUND");
        assertThat(mapper.readTree(notFound.getResponse().getContentAsString()).has("reference")).isFalse();
        fakes.outcome.set(new AccountInactive());
        problem(mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn(),
                409, "ACCOUNT_INACTIVE");
        fakes.outcome.set(new InsufficientBalance());
        var insufficient = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        problem(insufficient, 422, "INSUFFICIENT_BALANCE");
        assertThat(mapper.readTree(insufficient.getResponse().getContentAsString()).has("reference")).isFalse();
    }

    @Test
    void sourceNotOwnedIs403WithOwnershipAudit() throws Exception {
        fakes.outcome.set(new SourceNotOwned());
        var result = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
        problem(result, 403, "AUTH_FORBIDDEN");
        assertThat(fakes.events).anySatisfy(event -> {
            assertThat(event.action()).isEqualTo("SECURITY_ACCESS_DENIED");
            assertThat(event.afterState()).containsEntry("reasonCode", "OWNERSHIP_DENIED");
        });
    }

    @Test
    void staffCannotCreateAndAnonymousIs401() throws Exception {
        problem(mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer staff-read")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn(),
                403, "AUTH_FORBIDDEN");
        problem(mvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn(), 401, "AUTH_SESSION_EXPIRED");
    }

    @Test
    void cookieSessionRequiresCsrfWhileBearerIsExempt() throws Exception {
        problem(mvc.perform(post("/api/v1/transfers").cookie(new Cookie("DB_SESSION", "customer-a"))
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn(),
                403, "AUTH_FORBIDDEN");
        mvc.perform(post("/api/v1/transfers").cookie(new Cookie("DB_SESSION", "customer-a"))
                .header("X-CSRF-Token", "csrf-a").header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isCreated());
    }

    @Test
    void getParticipantReturnsMaskedDetailWithoutFraudFlagsAndNoStore() throws Exception {
        mvc.perform(get("/api/v1/transfers/" + REF).header("Authorization", "Bearer customer-a"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.reference").value(REF))
                .andExpect(jsonPath("$.sourceAccount.accountNumberMasked").value("**** **** 3001"))
                .andExpect(jsonPath("$.fraudFlags").doesNotExist());
    }

    @Test
    void getInvalidReferenceIs400AndAbsentIs404() throws Exception {
        problem(mvc.perform(get("/api/v1/transfers/not-a-reference").header("Authorization", "Bearer customer-a"))
                .andReturn(), 400, "VALIDATION_ERROR");
        fakes.stored.set(Optional.empty());
        problem(mvc.perform(get("/api/v1/transfers/" + REF).header("Authorization", "Bearer customer-a"))
                .andReturn(), 404, "TRANSFER_NOT_FOUND");
    }

    @Test
    void getNonParticipantIs403WithAudit() throws Exception {
        // customer-c (13) owns neither source 3001 nor destination 3002.
        var denied = mvc.perform(get("/api/v1/transfers/" + REF).header("Authorization", "Bearer customer-c")).andReturn();
        problem(denied, 403, "AUTH_FORBIDDEN");
        assertThat(fakes.events).anySatisfy(event -> assertThat(event.action()).isEqualTo("SECURITY_ACCESS_DENIED"));
    }

    @Test
    void staffFraudFlagsAreGatedByPermission() throws Exception {
        mvc.perform(get("/api/v1/transfers/" + REF).header("Authorization", "Bearer staff-read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraudFlags").doesNotExist());
        mvc.perform(get("/api/v1/transfers/" + REF).header("Authorization", "Bearer staff-fraud"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraudFlags[0].ruleCode").value("VELOCITY"))
                .andExpect(jsonPath("$.fraudFlags[0].ruleVersion").value(3));
    }

    private void problem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getHeader("X-Correlation-ID")).isNotBlank();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("correlationId").asText()).isEqualTo(result.getResponse().getHeader("X-Correlation-ID"));
        assertThat(body.path("retryable").isBoolean()).isTrue();
    }

    static TransferView view(TransferStatus status, TransferFailureCode failureCode) {
        return new TransferView(9001L, REF, status, new TransferAmount(new BigDecimal("150000.00"), "VND"),
                "Chuyen tien", new MaskedAccount(3001L, "**** **** 3001", "3001"),
                new MaskedAccount(3002L, "**** **** 6789", "6789"), "corr-original", NOW,
                status == TransferStatus.COMPLETED ? NOW : null,
                status == TransferStatus.FAILED ? NOW : null, failureCode,
                failureCode == null ? null : "An toàn.");
    }

    private static Object responseJson() {
        return java.util.Map.of("reference", REF, "status", "COMPLETED", "amount", "150000.00",
                "currency", "VND", "correlationId", "corr-original", "createdAt", "2026-11-16T10:15:30Z");
    }

    @TestConfiguration
    static class Fakes {
        final AtomicReference<TransferCreateOutcome> outcome = new AtomicReference<>();
        final AtomicReference<Optional<TransferView>> stored = new AtomicReference<>(Optional.empty());
        final List<AuditEvent> events = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger orchestratorCalls = new java.util.concurrent.atomic.AtomicInteger();

        @Bean SessionAuthenticator sessionAuthenticator() {
            return token -> Optional.ofNullable(switch (token) {
                case "customer-a" -> session("1", "11", "csrf-a", "ROLE_CUSTOMER", "TRANSFER_CREATE", "TRANSACTION_READ_SELF");
                case "customer-b" -> session("2", "12", "csrf-b", "ROLE_CUSTOMER", "TRANSFER_CREATE", "TRANSACTION_READ_SELF");
                case "customer-c" -> session("3", "13", "csrf-c", "ROLE_CUSTOMER", "TRANSFER_CREATE", "TRANSACTION_READ_SELF");
                case "staff-read" -> session("4", null, "csrf-d", "ROLE_OPERATIONS", "TRANSACTION_READ");
                case "staff-fraud" -> session("5", null, "csrf-e", "ROLE_ADMIN", "TRANSACTION_READ", "FRAUD_READ");
                default -> null;
            });
        }
        private static SessionAuthenticator.Session session(String user, String customer, String csrf, String... auth) {
            return new SessionAuthenticator.Session(user, customer, Set.of(auth), HmacService.sha256Hex(csrf));
        }
        @Bean TransferOrchestrator orchestrator() {
            return command -> { orchestratorCalls.incrementAndGet(); return outcome.get(); };
        }
        @Bean TransferReadPort readPort() { return reference -> stored.get(); }
        @Bean AccountOwnershipPort ownershipPort() {
            return (customerId, accountId) -> (customerId == 11L && accountId == 3001L)
                    || (customerId == 12L && accountId == 3002L) ? Ownership.OWNED : Ownership.FORBIDDEN;
        }
        @Bean FraudFlagReadPort fraudPort() {
            return transferId -> List.of(new FraudFlagView(7L, "VELOCITY", 3, "HIGH", "OPEN"));
        }
        @Bean AuditEventPort auditEventPort() {
            return event -> { events.add(event); return Optional.of(1L); };
        }
    }
}