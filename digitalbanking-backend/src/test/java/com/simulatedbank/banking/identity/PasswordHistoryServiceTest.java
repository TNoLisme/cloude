package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.application.PasswordHasher;
import com.simulatedbank.banking.identity.application.port.PasswordHistoryRepository;
import com.simulatedbank.banking.identity.application.PasswordHistoryService;
import com.simulatedbank.banking.identity.application.PasswordPolicyProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordHistoryServiceTest {
    private final PasswordHistoryRepository history = mock(PasswordHistoryRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final PasswordHistoryService service = new PasswordHistoryService(history, hasher,
            new PasswordPolicyProperties(3, 8, 128));

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Aa1!", "lowercase1!", "UPPERCASE1!", "NoDigits!", "NoSpecial12", "White space12"})
    void invalidComplexityNeverTouchesCredentialsAndErasesCandidate(String input) {
        char[] candidate = input == null ? null : input.toCharArray();
        assertThatThrownBy(() -> service.replacePassword(42L, candidate))
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(history, hasher);
        if (candidate != null) {
            assertThat(candidate).isEqualTo(new char[candidate.length]);
        }
    }
}
