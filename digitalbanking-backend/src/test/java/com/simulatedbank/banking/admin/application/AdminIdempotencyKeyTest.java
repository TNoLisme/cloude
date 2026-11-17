package com.simulatedbank.banking.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

/** BE-ADM-CON-03: header key contract from 07 §5.2. */
class AdminIdempotencyKeyTest {

    @Test
    void acceptsTheDocumentedKeyPattern() {
        assertThat(AdminIdempotencyService.requireKey("7c9e6679-7425-40de-944b-e07fc1f90ae7"))
                .isEqualTo("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        assertThat(AdminIdempotencyService.requireKey("A.b_c:~-123")).isEqualTo("A.b_c:~-123");
        assertThat(AdminIdempotencyService.requireKey("a".repeat(255))).hasSize(255);
    }

    @Test
    void rejectsMissingMalformedAndOversizedKeys() {
        for (String invalid : new String[] {null, "", " ", "has space", "a".repeat(256), "unicode-é", "semi;colon"}) {
            assertThatThrownBy(() -> AdminIdempotencyService.requireKey(invalid))
                    .isInstanceOfSatisfying(com.simulatedbank.banking.common.error.BankingValidationException.class,
                            error -> {
                                assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                                assertThat(error.field()).isEqualTo("Idempotency-Key");
                            });
        }
    }
}
