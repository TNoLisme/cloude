package com.simulatedbank.banking.identity.adapter.in;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.regex.Pattern;

/** Contract checks used inside secret-owning controllers' try/finally boundaries. */
final class IdentityRequestValidation {
    static final String UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    static final String BASE64URL_REGEX = "[A-Za-z0-9_-]+={0,2}";
    private static final Pattern UUID = Pattern.compile(UUID_REGEX);
    private static final Pattern BASE64URL = Pattern.compile(BASE64URL_REGEX);

    private IdentityRequestValidation() {}

    static void requireUuid(String value) {
        if (value == null || !UUID.matcher(value).matches()) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    static void requireDigits(char[] value) {
        if (value == null || value.length < 6 || value.length > 8) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        for (char digit : value) {
            if (digit < '0' || digit > '9') {
                throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
            }
        }
    }

    static void requireBase64Url(String value) {
        if (value == null || !BASE64URL.matcher(value).matches()) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
