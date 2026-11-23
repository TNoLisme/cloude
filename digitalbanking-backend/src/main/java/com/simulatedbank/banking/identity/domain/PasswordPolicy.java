package com.simulatedbank.banking.identity.domain;

/** Pure BRL-006 complexity rule; thresholds are supplied by application configuration. */
public final class PasswordPolicy {
    private PasswordPolicy() {
    }

    public static boolean accepts(char[] password, int minLength, int maxLength) {
        if (password == null || password.length < minLength || password.length > maxLength) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;
        for (char value : password) {
            upper |= Character.isUpperCase(value);
            lower |= Character.isLowerCase(value);
            digit |= Character.isDigit(value);
            special |= !Character.isLetterOrDigit(value) && !Character.isWhitespace(value)
                    && !Character.isISOControl(value);
        }
        return upper && lower && digit && special;
    }
}
