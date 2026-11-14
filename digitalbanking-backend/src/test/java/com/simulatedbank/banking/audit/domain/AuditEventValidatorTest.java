package com.simulatedbank.banking.audit.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventValidatorTest {

    @Test
    void acceptsNullAmountOnlyWhenCurrencyIsAlsoNull() {
        assertThatCode(() -> AuditEventValidator.validateAmountAndCurrency(null, null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsCurrencyWhenAmountIsNull() {
        assertThatThrownBy(() -> AuditEventValidator.validateAmountAndCurrency(null, "VND"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsNonNegativeVndAmountWithAtMostTwoFractionDigits() {
        assertThatCode(() -> AuditEventValidator.validateAmountAndCurrency(new BigDecimal("1250.50"), "VND"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> AuditEventValidator.validateAmountAndCurrency(new BigDecimal("-0.01"), "VND"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmountWithMoreThanTwoFractionDigits() {
        assertThatThrownBy(() -> AuditEventValidator.validateAmountAndCurrency(new BigDecimal("1.001"), "VND"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonVndCurrency() {
        assertThatThrownBy(() -> AuditEventValidator.validateAmountAndCurrency(new BigDecimal("1.00"), "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsKnownCatalogAction() {
        assertThatCode(() -> AuditActionCatalog.require("TRANSFER_COMPLETED")).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownCatalogAction() {
        assertThatThrownBy(() -> AuditActionCatalog.require("TRANSFER_SECRET"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsCatalogMetadataForTransferCompletion() {
        var action = AuditActionCatalog.require("TRANSFER_COMPLETED");
        assertThat(action.criticality()).isEqualTo(AuditActionCatalog.Criticality.C1);
        assertThat(action.allowedActorTypes()).containsExactly("USER");
        assertThat(action.entityType()).isEqualTo("TRANSFER");
        assertThat(action.requiredFields()).containsAll(Set.of(
                "amount", "currency", "before_state.status", "after_state.status"));
    }

    @Test
    void rejectsActorTypeOutsideCatalog() {
        var event = event("TRANSFER_COMPLETED", "ADMIN", 7L, "ADMIN", "TRANSFER", "TXN123",
                Map.of(), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", Map.of());
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUserActorWithoutUserId() {
        var event = event("TRANSFER_COMPLETED", "USER", null, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of(), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", Map.of());
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSystemActorWithoutUserId() {
        var event = event("TRANSFER_RECONCILED", "SYSTEM", null, "SYSTEM", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", Map.of());
        assertThatCode(() -> AuditEventValidator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void rejectsOutcomeOutsideCatalog() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of(), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", "OK", Map.of());
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCorrelationIdWithNewline() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of(), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr\nforged", "SUCCESS", Map.of());
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTransferCompletionWithoutAmountEvenWhenCurrencyIsNull() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), null, null,
                "corr-1", "SUCCESS", Map.of());

        assertThatThrownBy(() -> AuditEventValidator.validate(event))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEntityFieldOutsideAllowlist() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of(), Map.of("description", "sensitive"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", Map.of());
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventCopiesMutableStateMapsOnConstruction() {
        var afterState = new HashMap<String, Object>();
        afterState.put("status", "COMPLETED");
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of(), afterState, new BigDecimal("1.00"), "VND", "corr-1", "SUCCESS", Map.of());
        afterState.put("status", "FAILED");
        assertThat(event.afterState()).containsEntry("status", "COMPLETED");
    }

    @Test
    void rejectsTransferOnlyFieldInAccountSnapshot() {
        var event = event("ACCOUNT_STATUS_CHANGED", "USER", 7L, "ADMIN", "ACCOUNT", "42",
                Map.of("status", "ACTIVE"), Map.of("status", "BLOCKED", "reference", "TXN123"),
                null, null, "corr-1", "SUCCESS", Map.of());
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAccountStatusChangedCatalogAction() {
        var action = AuditActionCatalog.require("ACCOUNT_STATUS_CHANGED");
        assertThat(action.criticality()).isEqualTo(AuditActionCatalog.Criticality.C2);
        assertThat(action.allowedActorTypes()).containsExactly("USER");
        assertThat(action.entityType()).isEqualTo("ACCOUNT");
    }

    @Test
    void acceptsSourceBackedActionsWithCriticalityAndActorRules() {
        assertThat(AuditActionCatalog.require("AUTH_LOGIN_SUCCESS").criticality())
                .isEqualTo(AuditActionCatalog.Criticality.C2);
        assertThat(AuditActionCatalog.require("AUTH_LOGIN_FAILED").allowedActorTypes())
                .containsExactlyInAnyOrder("USER", "SYSTEM");
        assertThat(AuditActionCatalog.require("FRAUD_ENGINE_ERROR").criticality())
                .isEqualTo(AuditActionCatalog.Criticality.C4);
        assertThat(AuditActionCatalog.require("LEGAL_HOLD_PLACED").entityType()).isEqualTo("LEGAL_HOLD");
        assertThat(AuditActionCatalog.require("AUDIT_EXPORTED").criticality())
                .isEqualTo(AuditActionCatalog.Criticality.C2);
    }

    @Test
    void acceptsAccountSpecificAllowlistedFields() {
        var event = event("ACCOUNT_STATUS_CHANGED", "USER", 7L, "ADMIN", "ACCOUNT", "42",
                Map.of("status", "ACTIVE", "last4", "1234"),
                Map.of("status", "BLOCKED", "reasonCode", "RISK_REVIEW"),
                null, null, "corr-1", "SUCCESS", Map.of());
        assertThatCode(() -> AuditEventValidator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNestedSnapshotOverByteLimit() {
        var largeValues = java.util.Collections.nCopies(9000, "1234567890");
        assertThatThrownBy(() -> AuditEventValidator.validate(event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER",
                "TRANSFER", "TXN123", Map.of("status", "PENDING"), Map.of("status", largeValues),
                new BigDecimal("1.00"), "VND", "corr-1", "SUCCESS", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTransferCompletionWithoutRequiredAmount() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), null, null,
                "corr-1", "SUCCESS", Map.of());

        assertThatThrownBy(() -> AuditEventValidator.validate(event))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTransferEventMissingRequiredStateFields() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of(), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", Map.of());
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTransferCompletionWithoutRequiredBeforeStatus() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                null, Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", Map.of());
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWrongTypeForAllowlistedStatusField() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of("status", 1), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", Map.of());
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnmaskedIpInClientInfo() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), new BigDecimal("1.00"),
                "VND", "corr-1", "SUCCESS", Map.of("ipMasked", "203.0.113.7"));
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullOrBlankRequiredStatus() {
        for (String status : java.util.Arrays.asList(null, "", "  ")) {
            var after = new HashMap<String, Object>();
            after.put("status", status);
            var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                    Map.of("status", "PENDING"), after, new BigDecimal("1.00"), "VND",
                    "corr-1", "SUCCESS", null);
            assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsTransferCreationWithoutBeforeState() {
        var event = event("TRANSFER_CREATED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                null, Map.of("status", "PENDING"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", null);
        assertThatCode(() -> AuditEventValidator.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingCatalogRequiredReasonCode() {
        var event = event("AUTH_LOGIN_SUCCESS", "USER", 7L, "CUSTOMER", "APP_USER", "7",
                null, Map.of("status", "ACTIVE"), null, null,
                "corr-1", "SUCCESS", Map.of("ipMasked", "203.0.113.***"));
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSystemActorWithUserId() {
        var event = event("TRANSFER_RECONCILED", "SYSTEM", 7L, "SYSTEM", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullActorTypeWithValidationError() {
        var event = event("TRANSFER_COMPLETED", null, 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullOutcomeWithValidationError() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED"), new BigDecimal("1.00"), "VND",
                "corr-1", null, null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFullAccountNumberInLast4() {
        var event = event("ACCOUNT_STATUS_CHANGED", "USER", 7L, "ADMIN", "ACCOUNT", "42",
                Map.of("status", "ACTIVE"), Map.of("status", "BLOCKED", "last4", "1234567890123456"),
                null, null, "corr-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCyclicSnapshotBeforeRecursiveCopyOverflows() {
        var state = new HashMap<String, Object>();
        state.put("status", state);
        assertThatThrownBy(() -> event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                null, state, new BigDecimal("1.00"), "VND", "corr-1", "SUCCESS", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDeepListBeforeRecursiveCopy() {
        Object value = "COMPLETED";
        for (int i = 0; i < 20; i++) value = java.util.List.of(value);
        var state = Map.of("status", value);
        assertThatThrownBy(() -> event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                null, state, new BigDecimal("1.00"), "VND", "corr-1", "SUCCESS", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void catalogDeclaresSourceRequiredFieldsForNonTransferActions() {
        assertThat(AuditActionCatalog.require("AUTH_CHALLENGE_CREATED").requiredFields())
                .contains("after_state.attemptCount");
        assertThat(AuditActionCatalog.require("LIMIT_CHANGE_APPROVED").requiredFields())
                .contains("after_state.targetType", "after_state.targetId", "after_state.makerId",
                        "after_state.checkerId", "after_state.decision");
        assertThat(AuditActionCatalog.require("ACCOUNT_STATUS_CHANGED").requiredFields())
                .contains("after_state.reasonCode");
        assertThat(AuditActionCatalog.require("FRAUD_FLAG_CREATED").requiredFields())
                .contains("after_state.transferReference");
        assertThat(AuditActionCatalog.require("LEGAL_HOLD_PLACED").requiredFields())
                .contains("after_state.entityType", "after_state.entityId");
        assertThat(AuditActionCatalog.require("AUDIT_EXPORTED").requiredFields())
                .contains("after_state.rowCount", "after_state.from", "after_state.to", "after_state.format",
                        "after_state.filterHash");
        assertThat(AuditActionCatalog.require("RETENTION_PURGED").requiredFields())
                .contains("after_state.checkpointId");
    }

    @Test
    void rejectsEmailInSnapshotValue() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED", "failureCode", "user@example.com"),
                new BigDecimal("1.00"), "VND", "corr-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLongNumericSensitiveValueInSnapshot() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED", "failureCode", "0123456789012345"),
                new BigDecimal("1.00"), "VND", "corr-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPhoneLikeValueInSnapshot() {
        var event = event("TRANSFER_COMPLETED", "USER", 7L, "CUSTOMER", "TRANSFER", "TXN123",
                Map.of("status", "PENDING"), Map.of("status", "COMPLETED", "failureCode", "+84901234567"),
                new BigDecimal("1.00"), "VND", "corr-1", "SUCCESS", null);
        assertThatThrownBy(() -> AuditEventValidator.validate(event)).isInstanceOf(IllegalArgumentException.class);
    }

    private static AuditEvent event(String action, String actorType, Long actorUserId, String actorRole,
                                    String entityType, String entityId, Map<String, Object> beforeState,
                                    Map<String, Object> afterState, BigDecimal amount, String currency,
                                    String correlationId, String outcome, Map<String, Object> clientInfo) {
        return new AuditEvent(action, actorType, actorUserId, actorRole, entityType, entityId,
                beforeState, afterState, amount, currency, correlationId, "req-1", outcome, clientInfo);
    }
}
