package com.simulatedbank.banking.identity.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** BE-CON-64: defaults from 04 §4.2.7 / BRL-006; configuration cannot weaken their minimums. */
@ConfigurationProperties(prefix = "app.security.password")
@Validated
public record PasswordPolicyProperties(
        @DefaultValue("3") @Min(3) int historySize,
        @DefaultValue("8") @Min(8) int minLength,
        @DefaultValue("128") @Min(8) @Max(128) int maxLength) {

    @AssertTrue(message = "password length range must be ordered")
    public boolean isLengthRangeValid() {
        return minLength <= maxLength;
    }
}
