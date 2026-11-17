package com.simulatedbank.banking.common.error;

/**
 * Validation failure tied to one request field (header/body path). The shared Problem pipeline maps it to
 * {@code 400 VALIDATION_ERROR} with {@code errors[].field} per BE-CON-55, without exposing the rejected value or
 * parser/SQL details.
 */
public class BankingValidationException extends BankingBusinessException {

    private final String field;

    public BankingValidationException(String field, String safeDetail) {
        super(ErrorCode.VALIDATION_ERROR, safeDetail);
        if (field == null || !field.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Invalid validation field");
        }
        this.field = field;
    }

    public String field() {
        return field;
    }
}
