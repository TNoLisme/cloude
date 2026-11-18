package com.simulatedbank.banking.account.adapter.in;

import java.time.Instant;

/**
 * Wire DTO của OpenAPI {@code AccountDetail} (`07` §7.2.8, §8.2.3) — BE-ACC-05.
 *
 * <p>Kế thừa phẳng các field của {@link AccountSummaryResponse} (không dùng {@code allOf} vì
 * response là một object JSON) và thêm {@code owner}, {@code createdAt}, {@code updatedAt}.
 * Không trả số tài khoản đầy đủ, ciphertext, blind index hay PII ngoài projection được duyệt.</p>
 *
 * <p>Giới hạn đã biết (ACC-DR-10 BLOCKED): {@code owner.fullName} KHÔNG được trả vì
 * {@code customer.api} cấm field PII dạng text (ModuleBoundaryArchTest
 * {@code customerApiMustNotCarryTextualPiiFields}) và Customer chưa triển khai/duyệt
 * {@code getCustomerView} (design/05 §6.2). Account không đọc/decrypt PII Customer; xem
 * BE-ACC-05 §G.</p>
 */
public record AccountDetailResponse(
        String accountId,
        String accountNumberMasked,
        String accountNumberLast4,
        String currency,
        String status,
        String availableBalance,
        String ledgerBalance,
        Instant asOf,
        Owner owner,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Định danh chủ sở hữu do Account sở hữu (server-side, không phải actor hay giá trị client).
     * {@code fullName} thuộc ACC-DR-10 — chưa có provider được duyệt.
     */
    public record Owner(String customerId, String status) {
    }
}
