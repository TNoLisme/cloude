package com.bank.simulator;

import com.bank.simulator.audit.application.AuditEventRepository;
import com.bank.simulator.audit.application.AuditQuery;
import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.otp.OtpSender;
import com.bank.simulator.identity.infrastructure.otp.OtpMessage;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.risk.application.RiskFlagQuery;
import com.bank.simulator.risk.application.RiskFlagRepository;
import com.bank.simulator.shared.pagination.CursorPosition;
import com.bank.simulator.transfer.application.TransferService;
import com.bank.simulator.transfer.infrastructure.persistence.TransferJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class UiApiRegressionPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ui_regression_test").withUsername("test").withPassword("test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("app.security.jwt-secret", () -> "01234567890123456789012345678901");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TransferJdbcRepository transfers;
    @Autowired TransferService service;
    @Autowired IdentityJdbcRepository identities;
    @Autowired PinHashingService pins;
    @Autowired AccountNumberGenerator numbers;
    @Autowired AuditEventRepository audit;
    @Autowired RiskFlagRepository risk;
    @MockBean OtpSender sender;
    private final Instant now = Instant.parse("2026-10-08T10:00:00Z");

    @BeforeEach void resetDisposableTables() {
        jdbc.execute("TRUNCATE risk_flags, transfers, audit_events, idempotency_records, customer_pins, accounts, customers, user_roles, refresh_sessions, users CASCADE");
    }
    @Test void completedTransferSatisfiesConstraintAndReplayMovesBalancesOnlyOnce() {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, "0912345678", "regression@example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Regression customer", null, now);
        identities.setPin(customer, pins.encode("000001"), now);
        UUID source = identities.createDefaultAccount(customer, numbers, now).accountId();
        UUID destination = identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
        jdbc.update("UPDATE accounts SET balance=10000000 WHERE id=?", source);
        var actor = new AuthenticatedActor(user, Set.of("CUSTOMER"));
        var command = new TransferService.CreateCommand(source, destination, "5000000", "VND", "000001", "Regression");
        String key = UUID.randomUUID().toString();
        var result = service.create(actor, command, key);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(transfers.find(result.transferId()).completedAt()).isNotNull();
        assertThat(service.create(actor, command, key).replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, source)).isEqualTo("5000000");
        assertThat(jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, destination)).isEqualTo("5000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
    }
    @Test void historyHandlesAbsentFiltersDatesCursorAndRecipientVisibility() {
        UUID source = UUID.randomUUID(), destination = UUID.randomUUID();
        UUID old = transfers.create(source, destination, "2000", "VND", "COMPLETED", null, null, null, now.minusSeconds(1), null);
        UUID recent = transfers.create(source, destination, "2000", "VND", "COMPLETED", null, null, null, now, null);
        UUID pending = transfers.create(source, destination, "5000001", "VND", "AWAITING_OTP", null, null, null, now.plusSeconds(1), now.plusSeconds(121));
        assertThat(transfers.find(pending).completedAt()).isNull();
        assertThat(transfers.findHistory(List.of(source), null, null, null, null, null, 10)).hasSize(3);
        assertThat(transfers.findHistory(List.of(destination), null, null, null, null, null, 10))
                .extracting(TransferJdbcRepository.TransferRow::id).containsExactly(recent, old);
        assertThat(transfers.findHistory(List.of(source), "COMPLETED", now.minusSeconds(2), now.plusSeconds(2), now, recent, 10))
                .extracting(TransferJdbcRepository.TransferRow::id).containsExactly(old);
        assertThat(transfers.findHistory(List.of(source), null, now, null, null, null, 10)).hasSize(2);
    }
    @Test void auditAndRiskHandleAbsentFiltersAndTypedCursorFilters() {
        UUID actor = UUID.randomUUID(), transfer = UUID.randomUUID(), auditOld = UUID.randomUUID(), auditRecent = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            Instant time = now.plusSeconds(i);
            jdbc.update("INSERT INTO audit_events (id, actor_id, event_type, target_type, target_id, outcome, correlation_id, summary, occurred_at) VALUES (?,?,?,'TRANSFER',?,'SUCCESS',?,'Regression',?)",
                    i == 0 ? auditOld : auditRecent, actor, "TRANSFER_COMPLETED", transfer, UUID.randomUUID(), Timestamp.from(time));
        }
        assertThat(audit.find(new AuditQuery(10, null, null, null, null, null), null, 10)).hasSize(2);
        assertThat(audit.find(new AuditQuery(10, null, "TRANSFER_COMPLETED", actor, now.minusSeconds(1), now.plusSeconds(2)), new CursorPosition(1, now.plusSeconds(1), auditRecent), 10))
                .extracting(row -> row.eventId()).containsExactly(auditOld);
        risk.insert(transfer, "LARGE_TRANSFER", "1", "Regression", now);
        risk.insert(transfer, "LARGE_TRANSFER", "2", "Regression", now.plusSeconds(1));
        var all = risk.find(new RiskFlagQuery(10, null, null, null, null, null), null, 10);
        assertThat(all).hasSize(2);
        assertThat(risk.find(new RiskFlagQuery(10, null, "LARGE_TRANSFER", transfer, now.minusSeconds(1), now.plusSeconds(2)), new CursorPosition(1, all.getFirst().detectedAt(), all.getFirst().flagId()), 10))
                .extracting(row -> row.flagId()).containsExactly(all.get(1).flagId());
    }
    @Test void otpFailureAndExpiryPersistTerminalStatesWithoutChangingBalances() {
        UUID user = UUID.randomUUID(), customer = UUID.randomUUID();
        identities.createUser(user, "0912345679", "terminal@example.test", "unused-test-hash", "CUSTOMER", now);
        identities.createCustomer(customer, user, "Terminal regression", null, now);
        identities.setPin(customer, pins.encode("000001"), now);
        UUID source = identities.createDefaultAccount(customer, numbers, now).accountId();
        UUID destination = identities.createDefaultAccount(UUID.randomUUID(), numbers, now).accountId();
        jdbc.update("UPDATE accounts SET balance=10000000 WHERE id=?", source);
        var actor = new AuthenticatedActor(user, Set.of("CUSTOMER"));
        var command = new TransferService.CreateCommand(source, destination, "5000001", "VND", "000001", "Terminal regression");
        var failed = service.create(actor, command, UUID.randomUUID().toString());
        assertThat(failed.status()).isEqualTo("AWAITING_OTP");
        ArgumentCaptor<OtpMessage> message = ArgumentCaptor.forClass(OtpMessage.class);
        verify(sender).send(message.capture());
        String wrong = "999999".equals(message.getValue().code()) ? "999998" : "999999";
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.confirm(actor, failed.transferId(), wrong)).isInstanceOf(ApiException.class);
        }
        assertThat(transfers.find(failed.transferId()).status()).isEqualTo("FAILED");
        var expired = service.create(actor, command, UUID.randomUUID().toString());
        jdbc.update("UPDATE transfers SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), expired.transferId());
        assertThatThrownBy(() -> service.confirm(actor, expired.transferId(), "000000")).isInstanceOf(ApiException.class);
        assertThat(transfers.find(expired.transferId()).status()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, source)).isEqualTo("10000000");
        assertThat(jdbc.queryForObject("SELECT balance::text FROM accounts WHERE id=?", String.class, destination)).isEqualTo("0");
    }
}
