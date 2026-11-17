package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.transfer.application.StepUpChallengePort.ChallengeIssued;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort.ChallengeRequest;
import com.simulatedbank.banking.transfer.application.StepUpChallengePort.VerifiedChallenge;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Consumer-seam DTO invariants for TRFC-ID-02/05; provider behavior is exercised via fakes elsewhere. */
class StepUpChallengePortTest {
    private static final String HASH = "a".repeat(64);
    private static final UUID REF = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final Instant EXPIRY = Instant.parse("2026-11-16T10:20:30Z");

    @Test
    void challengeRequestRequiresServerActorPurposeHashKeyAndCorrelation() {
        ChallengeRequest valid = new ChallengeRequest(2, 1, "TRANSFER_STEP_UP", HASH, "key", "corr");
        assertThat(valid.userId()).isEqualTo(2);
        assertThatThrownBy(() -> new ChallengeRequest(0, 1, "TRANSFER_STEP_UP", HASH, "key", "corr"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChallengeRequest(2, 0, "TRANSFER_STEP_UP", HASH, "key", "corr"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChallengeRequest(2, 1, "SENSITIVE_ACTION", HASH, "key", "corr"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChallengeRequest(2, 1, "TRANSFER_STEP_UP", "not-a-hash", "key", "corr"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChallengeRequest(2, 1, "TRANSFER_STEP_UP", HASH, "bad key!", "corr"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChallengeRequest(2, 1, "TRANSFER_STEP_UP", HASH, "key", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void challengeRequestStringFormNeverExposesKeyOrHash() {
        ChallengeRequest request = new ChallengeRequest(2, 1, "TRANSFER_STEP_UP", HASH, "secret-key", "corr");
        assertThat(request.toString()).doesNotContain("secret-key", HASH);
    }

    @Test
    void challengeIssuedCarriesSafeDisplayFieldsAndValidAttempts() {
        ChallengeIssued issued = new ChallengeIssued(REF, "OTP", List.of("OTP", "FIDO"), "SMS",
                "********123", EXPIRY, 5, 5);
        assertThat(issued.challengeRef()).isEqualTo(REF);
        assertThat(issued.methods()).containsExactly("OTP", "FIDO");
        assertThatThrownBy(() -> new ChallengeIssued(REF, "OTP", List.of(), "SMS", null, EXPIRY, 5, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChallengeIssued(REF, "OTP", List.of("OTP"), "SMS", null, EXPIRY, 5, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChallengeIssued(REF, "OTP", List.of("OTP"), "SMS", null, EXPIRY, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChallengeIssued(null, "OTP", List.of("OTP"), "SMS", null, EXPIRY, 5, 5))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChallengeIssued(REF, "OTP", List.of("OTP"), "SMS", null, null, 5, 5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void challengeIssuedMethodsAreAnImmutableCopy() {
        List<String> mutable = new ArrayList<>(List.of("OTP"));
        ChallengeIssued issued = new ChallengeIssued(REF, "OTP", mutable, "SMS", null, EXPIRY, 5, 5);
        mutable.add("FIDO");
        assertThat(issued.methods()).containsExactly("OTP");
        assertThatThrownBy(() -> issued.methods().add("PASSCODE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void verifiedChallengeSnapshotRequiresReferenceAndExpiry() {
        VerifiedChallenge snapshot = new VerifiedChallenge(REF, EXPIRY);
        assertThat(snapshot.challengeRef()).isEqualTo(REF);
        assertThatThrownBy(() -> new VerifiedChallenge(null, EXPIRY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VerifiedChallenge(REF, null)).isInstanceOf(NullPointerException.class);
    }
}