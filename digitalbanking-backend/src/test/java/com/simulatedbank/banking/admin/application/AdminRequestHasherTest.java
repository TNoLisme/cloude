package com.simulatedbank.banking.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.LimitChangePayload;
import com.simulatedbank.banking.admin.domain.RoleChangeOperation;
import com.simulatedbank.banking.admin.domain.RoleChangePayload;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** BE-ADM-CON-03: canonical request hash must be deterministic, normalized and field-sensitive. */
class AdminRequestHasherTest {

    private static final long ACTOR = 1100L;
    private static final long APPROVAL = 8001L;

    private final AdminRequestHasher hasher = new AdminRequestHasher(new JcsCanonicalizer());

    @Test
    void roleHashExcludesServerCapturedExpectedVersion() {
        RoleChangePayload first = role(3L, "reason");
        RoleChangePayload retry = role(9L, " reason ");

        assertThat(hasher.roleProposalHash(ACTOR, first))
                .isEqualTo(hasher.roleProposalHash(ACTOR, retry))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void roleHashChangesWithAnyBusinessField() {
        String baseline = hasher.roleProposalHash(ACTOR, role(3L, "reason"));

        assertThat(hasher.roleProposalHash(ACTOR + 1, role(3L, "reason"))).isNotEqualTo(baseline);
        assertThat(hasher.roleProposalHash(ACTOR, new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE,
                1006L, "OPERATIONS", null, 3L, "reason"))).isNotEqualTo(baseline);
        assertThat(hasher.roleProposalHash(ACTOR, new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE,
                1005L, "AUDITOR", null, 3L, "reason"))).isNotEqualTo(baseline);
        assertThat(hasher.roleProposalHash(ACTOR, role(3L, "different"))).isNotEqualTo(baseline);
    }

    @Test
    void limitHashNormalizesMoneyAndInstants() {
        LimitChangePayload first = limit(new BigDecimal("5000000.0"), new BigDecimal("20000000.00"),
                Instant.parse("2026-11-18T00:00:00Z"), " seasonal ");
        LimitChangePayload equivalent = limit(new BigDecimal("5000000.00"), new BigDecimal("20000000"),
                Instant.parse("2026-11-18T00:00:00.000Z"), "seasonal");

        assertThat(hasher.limitProposalHash(ACTOR, first))
                .isEqualTo(hasher.limitProposalHash(ACTOR, equivalent))
                .matches("[0-9a-f]{64}");
        assertThat(hasher.limitProposalHash(ACTOR, limit(new BigDecimal("5000001.00"),
                new BigDecimal("20000000.00"), Instant.parse("2026-11-18T00:00:00Z"), "seasonal")))
                .isNotEqualTo(hasher.limitProposalHash(ACTOR, equivalent));
    }

    @Test
    void decisionHashNormalizesNoteAndSeparatesActorDecisionAndApproval() {
        String baseline = hasher.decisionHash(ACTOR, APPROVAL, ApprovalDecision.APPROVE, " note ");

        assertThat(hasher.decisionHash(ACTOR, APPROVAL, ApprovalDecision.APPROVE, "note")).isEqualTo(baseline);
        assertThat(hasher.decisionHash(ACTOR, APPROVAL, ApprovalDecision.APPROVE, null))
                .isEqualTo(hasher.decisionHash(ACTOR, APPROVAL, ApprovalDecision.APPROVE, ""));
        assertThat(hasher.decisionHash(ACTOR, APPROVAL, ApprovalDecision.REJECT, "note"))
                .isNotEqualTo(baseline);
        assertThat(hasher.decisionHash(ACTOR + 1, APPROVAL, ApprovalDecision.APPROVE, "note"))
                .isNotEqualTo(baseline);
        assertThat(hasher.decisionHash(ACTOR, APPROVAL + 1, ApprovalDecision.APPROVE, "note"))
                .isNotEqualTo(baseline);
    }

    @Test
    void rejectedIdentifiersFailClosed() {
        assertThatThrownBy(() -> hasher.decisionHash(0, APPROVAL, ApprovalDecision.APPROVE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.decisionHash(ACTOR, 0, ApprovalDecision.APPROVE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.decisionHash(ACTOR, APPROVAL, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RoleChangePayload role(long expectedVersion, String reason) {
        return new RoleChangePayload(RoleChangeOperation.ASSIGN_ROLE, 1005L, "OPERATIONS", null,
                expectedVersion, reason);
    }

    private static LimitChangePayload limit(BigDecimal perTransaction, BigDecimal daily, Instant effectiveFrom,
            String reason) {
        return new LimitChangePayload(2001L, null, perTransaction, daily, "VND", effectiveFrom, reason);
    }
}
