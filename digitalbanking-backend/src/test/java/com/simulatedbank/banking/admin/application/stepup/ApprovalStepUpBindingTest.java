package com.simulatedbank.banking.admin.application.stepup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.admin.application.port.ApprovalPayloadCanonicalizer;
import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.LimitChangePayload;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** BE-ADM-CON-02: canonical binding/hash unit contract (no database, no client-supplied hash). */
class ApprovalStepUpBindingTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final long MAKER = 1100L;
    private static final long CHECKER = 1101L;
    private static final long TARGET = 1105L;

    /** Test double: production uses the Common RFC 8785 canonicalizer through the Admin adapter. */
    private static final ApprovalPayloadCanonicalizer PAYLOADS =
            payload -> payload.toString().getBytes(StandardCharsets.UTF_8);

    private final ApprovalStepUpBinding binding = new ApprovalStepUpBinding(PAYLOADS);

    @Test
    void bindingKeyIsThePersistedApprovalId() {
        assertThat(binding.bindingKey(42L)).isEqualTo("42");
    }

    @Test
    void missingOrNonPositiveApprovalIdIsRejected() {
        assertCode(() -> binding.bindingKey(0L), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> binding.bindingKey(-7L), ErrorCode.VALIDATION_ERROR);

        ApprovalRequest unpersisted = ApprovalRequest.propose(rolePayload(), MAKER, "corr", NOW);
        assertCode(() -> binding.decisionRequestHash(unpersisted, CHECKER, ApprovalDecision.APPROVE, null),
                ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void hashIsDeterministicLowercaseSha256() {
        ApprovalRequest approval = approval(1L, MAKER, rolePayload());
        String first = binding.decisionRequestHash(approval, CHECKER, ApprovalDecision.APPROVE, " note ");
        String second = binding.decisionRequestHash(approval, CHECKER, ApprovalDecision.APPROVE, "note");

        assertThat(first).matches("[0-9a-f]{64}").isEqualTo(second);
    }

    @Test
    void everySecurityRelevantFieldChangesTheHash() {
        ApprovalRequest approval = approval(1L, MAKER, rolePayload());
        String baseline = binding.decisionRequestHash(approval, CHECKER, ApprovalDecision.APPROVE, "note");

        assertThat(binding.decisionRequestHash(approval(2L, MAKER, rolePayload()), CHECKER,
                ApprovalDecision.APPROVE, "note")).isNotEqualTo(baseline);
        assertThat(binding.decisionRequestHash(approval(1L, MAKER + 1, rolePayload()), CHECKER,
                ApprovalDecision.APPROVE, "note")).isNotEqualTo(baseline);
        assertThat(binding.decisionRequestHash(approval, CHECKER + 1, ApprovalDecision.APPROVE, "note"))
                .isNotEqualTo(baseline);
        assertThat(binding.decisionRequestHash(approval, CHECKER, ApprovalDecision.REJECT, "note"))
                .isNotEqualTo(baseline);
        assertThat(binding.decisionRequestHash(approval, CHECKER, ApprovalDecision.APPROVE, "other"))
                .isNotEqualTo(baseline);
        assertThat(binding.decisionRequestHash(approval, CHECKER, ApprovalDecision.APPROVE, null))
                .isNotEqualTo(baseline);
    }

    @Test
    void changedPayloadChangesTheHash() {
        ApprovalRequest first = approval(1L, MAKER, rolePayload());
        ApprovalRequest changed = approval(1L, MAKER, new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE,
                TARGET, "OPERATIONS", null, 0L, "reason"));

        assertThat(binding.decisionRequestHash(changed, CHECKER, ApprovalDecision.APPROVE, "note"))
                .isNotEqualTo(binding.decisionRequestHash(first, CHECKER, ApprovalDecision.APPROVE, "note"));
    }

    @Test
    void limitPayloadBoundThroughTheSameCanonicalForm() {
        ApprovalRequest approval = approval(9L, MAKER, new LimitChangePayload(2001L, null,
                new BigDecimal("5000000.00"), new BigDecimal("20000000.00"), "VND", null, "limit"));
        String hash = binding.decisionRequestHash(approval, CHECKER, ApprovalDecision.APPROVE, null);

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(binding.decisionRequestHash(approval, CHECKER, ApprovalDecision.REJECT, null))
                .isNotEqualTo(hash);
    }

    @Test
    void overlongDecisionNoteIsRejectedBeforeAnyChallengeExists() {
        ApprovalRequest approval = approval(1L, MAKER, rolePayload());
        assertCode(() -> binding.decisionRequestHash(approval, CHECKER, ApprovalDecision.APPROVE,
                "x".repeat(501)), ErrorCode.VALIDATION_ERROR);
    }

    private static ApprovalRequest approval(long id, long maker, com.simulatedbank.banking.admin.domain.ChangePayload payload) {
        return ApprovalRequest.restore(id, payload, maker, "corr-1", NOW, ApprovalStatus.PENDING, null, null,
                null, null);
    }

    private static RoleChangePayload rolePayload() {
        return new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, TARGET, "AUDITOR", null, 0L, "reason");
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BankingBusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
