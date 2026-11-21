package com.simulatedbank.banking.admin.application.query;

/** Documented page bounds shared by the Admin approval list (07 §2.5, OpenAPI {@code Page}/{@code Size}). */
public final class ApprovalQueryLimits {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private ApprovalQueryLimits() {
    }
}
