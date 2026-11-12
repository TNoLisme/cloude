package com.simulatedbank.banking.common.config;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
        @DefaultValue("Asia/Ho_Chi_Minh") String timezone,
        @DefaultValue Crypto crypto,
        @DefaultValue @Valid Security security,
        @DefaultValue Transfer transfer,
        @DefaultValue Fraud fraud,
        @DefaultValue Jobs jobs
) {

    public record Crypto(
            @DefaultValue("k-v1") String aesKeyId,
            String aesKeyBase64,
            String hmacKeyBase64,
            @DefaultValue List<String> previousKeys
    ) {
        @Override
        public String toString() {
            return "Crypto[redacted]";
        }
    }

    public record Security(
            @DefaultValue Cookie cookie,
            @DefaultValue("http://localhost:5173") List<String> corsAllowedOrigins,
            @DefaultValue Session session,
            @DefaultValue @Valid Lockout lockout,
            @DefaultValue Otp otp,
            @DefaultValue @Valid RateLimit rateLimit,
            @DefaultValue StepUp stepUp
    ) {
        public record Cookie(
                @DefaultValue("DB_SESSION") String name,
                @DefaultValue("false") boolean secure,
                @DefaultValue("Strict") String sameSite,
                @DefaultValue("/") String path,
                @DefaultValue("28800") long maxAgeSeconds
        ) {
        }

        public record Session(
                @DefaultValue("15") int idleTimeoutMinutes,
                @DefaultValue("8") int absoluteTimeoutHours
        ) {
        }

        public record Lockout(
                @DefaultValue("5") @Positive int maxFailedAttempts,
                @DefaultValue("10") @Positive int lockDurationMinutes
        ) {
        }

        public record Otp(
                @DefaultValue("300") int ttlSeconds,
                @DefaultValue("120") int recoveryTtlSeconds,
                @DefaultValue("5") int maxVerifyAttempts,
                @DefaultValue("60") int resendCooldownSeconds,
                @DefaultValue("3") int maxResendCount
        ) {
        }

        public record RateLimit(
                @DefaultValue("10") int loginPerIpPerMinute,
                @DefaultValue("5") int loginPerUsernamePerMinute,
                @DefaultValue("10") int stepUpPerUserPer5Minutes,
                @DefaultValue("20") int otpVerifyPerUserPerMinute,
                @DefaultValue("30") @Positive int transferPerCustomerPerMinute,
                @DefaultValue("5") int auditExportPerUserPerHour,
                @DefaultValue("120") int readPerUserPerMinute
        ) {
        }

        public record StepUp(
                @DefaultValue("10000000") BigDecimal singleAmountVnd,
                @DefaultValue("20000000") BigDecimal dailyAmountVnd
        ) {
        }
    }

    public record Transfer(
            @DefaultValue("2000") BigDecimal minAmountVnd,
            @DefaultValue("10000000") BigDecimal maxAmountVnd,
            @DefaultValue("5000000") BigDecimal pinOtpThresholdVnd,
            @DefaultValue("50000000") BigDecimal dailyCapVnd,
            @DefaultValue("24") int idempotencyTtlHours,
            @DefaultValue("2000") long t2LockTimeoutMs,
            @DefaultValue("5000") long t2StatementTimeoutMs,
            @DefaultValue("1000") long fraudEvalTimeoutMs
    ) {
    }

    public record Fraud(
            @DefaultValue("3") int velocityCount,
            @DefaultValue("120") int velocityWindowSeconds,
            @DefaultValue("15") int warningTtlMinutes,
            @DefaultValue("30") int reviewTimeoutMinutes
    ) {
    }

    public record Jobs(
            @DefaultValue("true") boolean reconciliationOnStartup,
            @DefaultValue("60000") long reconciliationIntervalMs,
            @DefaultValue("300") long reconciliationPendingAgeSeconds,
            @DefaultValue("2000") long outboxPollIntervalMs,
            @DefaultValue("50") int outboxBatchSize,
            @DefaultValue("30") int outboxLeaseSeconds,
            @DefaultValue("0 */15 * * * *") String auditVerifyIncrementalCron,
            @DefaultValue("0 0 2 * * *") String auditVerifyFullCron,
            @DefaultValue("0 0 3 * * *") String retentionCleanupCron
    ) {
    }
}
