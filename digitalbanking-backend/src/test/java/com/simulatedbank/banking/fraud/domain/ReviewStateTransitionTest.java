package com.simulatedbank.banking.fraud.domain;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewStateTransitionTest {

    private final ReviewStateTransition stateTransition = new ReviewStateTransition();

    @ParameterizedTest(name = "Decision {0} maps to target FlagStatus {1}")
    @CsvSource({
            "ALLOW, ALLOWED",
            "BLOCK, BLOCKED",
            "ESCALATE, UNDER_REVIEW",
            "CLOSE, CLOSED"
    })
    @DisplayName("Review decision maps to expected target status per 07 §8.7")
    void decisionMapsToExpectedStatus(ReviewDecision decision, FlagStatus expectedStatus) {
        assertThat(decision.toTargetStatus()).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("Reviewing OPEN or UNDER_REVIEW flag succeeds")
    void reviewingNonTerminalFlagSucceeds() {
        assertThat(stateTransition.transition(FlagStatus.OPEN, ReviewDecision.ALLOW, "Customer verified"))
                .isEqualTo(FlagStatus.ALLOWED);
        assertThat(stateTransition.transition(FlagStatus.UNDER_REVIEW, ReviewDecision.BLOCK, "Confirmed fraud"))
                .isEqualTo(FlagStatus.BLOCKED);
        assertThat(stateTransition.transition(FlagStatus.OPEN, ReviewDecision.ESCALATE, "Escalated to L2"))
                .isEqualTo(FlagStatus.UNDER_REVIEW);
        assertThat(stateTransition.transition(FlagStatus.UNDER_REVIEW, ReviewDecision.CLOSE, "Administrative close"))
                .isEqualTo(FlagStatus.CLOSED);
    }

    @ParameterizedTest(name = "Reviewing terminal status {0} throws 409 FRAUD_FLAG_ALREADY_CLOSED")
    @CsvSource({
            "ALLOWED",
            "BLOCKED",
            "CLOSED"
    })
    @DisplayName("Reviewing terminal flag is rejected with 409 Conflict")
    void reviewingTerminalFlagThrowsConflict(FlagStatus terminalStatus) {
        assertThatThrownBy(() -> stateTransition.transition(terminalStatus, ReviewDecision.ALLOW, "Notes"))
                .isInstanceOf(BankingBusinessException.class)
                .satisfies(ex -> {
                    BankingBusinessException bbe = (BankingBusinessException) ex;
                    assertThat(bbe.code()).isEqualTo(ErrorCode.FRAUD_FLAG_ALREADY_CLOSED);
                    assertThat(bbe.code().httpStatus()).isEqualTo(409);
                });
    }

    @Test
    @DisplayName("Review notes longer than 500 characters are rejected")
    void rejectsNotesLongerThan500() {
        String longNote = "a".repeat(501);
        assertThatThrownBy(() -> stateTransition.transition(FlagStatus.OPEN, ReviewDecision.ALLOW, longNote))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("Review notes with raw credentials or sensitive PII are rejected")
    void rejectsSensitivePiiInNotes() {
        assertThatThrownBy(() -> stateTransition.transition(FlagStatus.OPEN, ReviewDecision.ALLOW, "Verified otp: 123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sensitive");

        assertThatThrownBy(() -> stateTransition.transition(FlagStatus.OPEN, ReviewDecision.ALLOW, "Customer password is testpass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sensitive");
    }
}
