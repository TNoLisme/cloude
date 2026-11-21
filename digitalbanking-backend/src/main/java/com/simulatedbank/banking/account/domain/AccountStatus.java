package com.simulatedbank.banking.account.domain;

/**
 * Trạng thái lưu trữ của {@code banking.account.status} (BE-ACC-03).
 *
 * <p>Nguồn canonical: {@code docs-common/04. Database design & analysis.md} §4.3.1 CHECK
 * {@code ck_account_status}, {@code docs-common/07. API contracts.md} §7.2.7 và OpenAPI
 * {@code AccountStatus}. Chỉ ba giá trị đã duyệt — không tự thêm {@code FROZEN} hay trạng thái
 * trung gian (DEC-ACC-001). Transition matrix và endpoint đổi trạng thái chưa được duyệt
 * (ACC-DR-12 BLOCKED), nên enum này chỉ mô tả dữ liệu đọc từ DB, không phải state machine.</p>
 */
public enum AccountStatus {
    ACTIVE,
    LOCKED,
    CLOSED
}
