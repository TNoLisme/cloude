package com.simulatedbank.banking.admin.domain;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Bounded text/identifier rules shared by Admin payloads and the step-up binding; never logs the rejected value. */
public final class AdminText {

    private AdminText() {
    }

    public static String optional(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        return bounded(normalized, maxLength);
    }

    public static String required(String value, int maxLength) {
        String normalized = optional(value, maxLength);
        if (normalized == null) {
            throw invalid();
        }
        return normalized;
    }

    static String bounded(String value, int maxLength) {
        if (value.length() > maxLength || containsUnsafeControlCharacter(value)) {
            throw invalid();
        }
        return value;
    }

    static long positiveId(long value) {
        if (value <= 0) {
            throw invalid();
        }
        return value;
    }

    static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw invalid();
        }
        BigDecimal normalized;
        try {
            normalized = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid();
        }
        if (normalized.precision() - normalized.scale() > 17) {
            throw invalid();
        }
        return normalized;
    }

    private static boolean containsUnsafeControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) && character != '\n' && character != '\t') {
                return true;
            }
        }
        return false;
    }

    static BankingBusinessException invalid() {
        return new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
    }
}
