package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record ApiPage<T>(List<T> items, Metadata page, Instant asOf) {
    public static <T> ApiPage<T> of(List<T> items, long total, Window window) {
        int pages = (int) Math.min(Integer.MAX_VALUE, (total + window.size() - 1) / window.size());
        return new ApiPage<>(items, new Metadata(window.page(), window.size(), total, pages,
                window.page() == 0, window.page() >= pages - 1), window.asOf());
    }

    public record Metadata(int number, int size, long totalElements, int totalPages, boolean first, boolean last) {}

    public record Window(Instant from, Instant to, int page, int size, Instant asOf) {
        public static Window resolve(Instant from, Instant to, int page, int size, Instant now) {
            Instant start = from == null ? now.minus(Duration.ofDays(30)) : from;
            Instant end = to == null ? now : to;
            if (page < 0 || size < 1 || size > 100 || !start.isBefore(end)
                    || Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0) {
                throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
            }
            return new Window(start, end, page, size, now);
        }

        public long offset() {
            return (long) page * size;
        }
    }
}
