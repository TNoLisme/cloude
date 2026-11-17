package com.simulatedbank.banking.fraud.application;

import com.simulatedbank.banking.fraud.application.dto.ReviewFraudFlagRequest;
import com.simulatedbank.banking.fraud.domain.ReviewDecision;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewFraudFlagRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("Valid request passes bean validation")
    void validRequestPasses() {
        ReviewFraudFlagRequest request = new ReviewFraudFlagRequest(
                ReviewDecision.ALLOW,
                "Customer verified via phone call"
        );
        Set<ConstraintViolation<ReviewFraudFlagRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Null decision fails bean validation")
    void nullDecisionFails() {
        ReviewFraudFlagRequest request = new ReviewFraudFlagRequest(
                null,
                "Valid notes"
        );
        Set<ConstraintViolation<ReviewFraudFlagRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("decision"));
    }

    @Test
    @DisplayName("Notes exceeding 500 characters fails bean validation")
    void notesTooLongFails() {
        String longNotes = "A".repeat(501);
        ReviewFraudFlagRequest request = new ReviewFraudFlagRequest(
                ReviewDecision.BLOCK,
                longNotes
        );
        Set<ConstraintViolation<ReviewFraudFlagRequest>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("notes"));
    }

    @Test
    @DisplayName("Security boundary: client DTO cannot specify or inject reviewerId")
    void clientDtoCannotSpecifyReviewerId() {
        RecordComponent[] components = ReviewFraudFlagRequest.class.getRecordComponents();
        assertThat(components).isNotNull();
        assertThat(components).hasSize(2);

        Set<String> componentNames = Set.of(
                components[0].getName(),
                components[1].getName()
        );
        assertThat(componentNames).containsExactlyInAnyOrder("decision", "notes");
        assertThat(componentNames).doesNotContain("reviewerId");
    }

    @Test
    @DisplayName("FraudReviewService validates trusted reviewerId format (> 0) when called with DTO (identity authentication & RBAC remain blocked)")
    void serviceRequiresTrustedReviewerIdWithDto() {
        Clock clock = Clock.systemUTC();
        FraudReviewService service = new FraudReviewService(null, null, clock);

        ReviewFraudFlagRequest request = new ReviewFraudFlagRequest(ReviewDecision.ALLOW, "Notes");

        assertThatThrownBy(() -> service.reviewFlag(100L, request, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewerId");

        assertThatThrownBy(() -> service.reviewFlag(100L, request, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewerId");

        assertThatThrownBy(() -> service.reviewFlag(100L, request, -10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewerId");

        assertThatThrownBy(() -> service.reviewFlag(100L, null, 999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request cannot be null");
    }
}
