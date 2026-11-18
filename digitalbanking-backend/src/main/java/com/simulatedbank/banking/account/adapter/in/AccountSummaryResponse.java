package com.simulatedbank.banking.account.adapter.in;

import java.time.Instant;

/**
 * Wire DTO của OpenAPI {@code AccountSummary} (`07` §7.2.7, §8.2.2) — BE-ACC-05.
 *
 * <p>DTO thuần của inbound adapter: không mang entity/projection nội bộ, không có số tài khoản
 * đầy đủ, ciphertext hay blind index. ID và tiền ra HTTP dưới dạng chuỗi decimal theo quy ước
 * OpenAPI (ID `[1-9][0-9]{0,18}`, money `(0|[1-9][0-9]{0,16})\.[0-9]{2}`); thời gian là
 * {@link Instant} UTC (Jackson serialize ISO-8601 vì {@code write-dates-as-timestamps=false}).</p>
 *
 * @param asOf thời điểm snapshot số dư — dùng chung một giá trị cho cả danh sách
 */
public record AccountSummaryResponse(
        String accountId,
        String accountNumberMasked,
        String accountNumberLast4,
        String currency,
        String status,
        String availableBalance,
        String ledgerBalance,
        Instant asOf) {
}
