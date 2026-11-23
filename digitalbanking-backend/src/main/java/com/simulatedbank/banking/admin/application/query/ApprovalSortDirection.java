package com.simulatedbank.banking.admin.application.query;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;

/** Direction token of the documented {@code field,asc|desc} sort syntax (07 §2.3). */
public enum ApprovalSortDirection {
    ASC,
    DESC;

    static ApprovalSortDirection fromToken(String token) {
        return switch (token) {
            case "asc" -> ASC;
            case "desc" -> DESC;
            default -> throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        };
    }
}
