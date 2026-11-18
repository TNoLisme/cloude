package com.simulatedbank.banking.account.adapter.in;

import java.time.Instant;
import java.util.List;

/**
 * Wire DTO của OpenAPI {@code AccountsResponse} (`07` §8.2.2) — BE-ACC-05.
 *
 * <p>{@code asOf} cấp response là snapshot time chung; {@code items} luôn là danh sách bất biến
 * (có thể rỗng khi customer không có tài khoản, không phải lỗi). Thứ tự {@code account_id ASC}
 * do persistence đã chốt.</p>
 */
public record AccountsResponse(List<AccountSummaryResponse> items, Instant asOf) {

    public AccountsResponse {
        items = List.copyOf(items);
    }
}
