package com.simulatedbank.banking.common.error;

import java.util.Map;
import java.util.Objects;

/** Owner-classified failure. Detail and extension values must already be safe for public output. */
public class BankingBusinessException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> extensions;

    public BankingBusinessException(ErrorCode code) {
        this(code, code.detail(), Map.of(), null);
    }

    public BankingBusinessException(ErrorCode code, String safeDetail) {
        this(code, safeDetail, Map.of(), null);
    }

    public BankingBusinessException(ErrorCode code, String safeDetail, Map<String, Object> extensions) {
        this(code, safeDetail, extensions, null);
    }

    public BankingBusinessException(ErrorCode code, String safeDetail, Map<String, Object> extensions,
            Throwable cause) {
        super(Objects.requireNonNull(safeDetail), cause);
        this.code = Objects.requireNonNull(code);
        this.extensions = ProblemResponse.checkedExtensions(code, extensions);
    }

    public ErrorCode code() { return code; }
    public String safeDetail() { return getMessage(); }
    public Map<String, Object> extensions() { return extensions; }
}
