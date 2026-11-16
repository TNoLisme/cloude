package com.simulatedbank.banking.admin.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ActionBinding;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LimitApprovalBinding {
    private static final Pattern UNSAFE = Pattern.compile(
            "(?i)[\\p{Cc}\\p{Cf}]|@|\\bBearer\\s|\\b(?:password|passcode|otp|token|secret)\\s*[:=]|(?<![0-9])[0-9]{9,20}(?![0-9])");
    private final JcsCanonicalizer canonicalizer;
    private final ObjectMapper json;

    public LimitApprovalBinding(JcsCanonicalizer canonicalizer, ObjectMapper json) {
        this.canonicalizer = canonicalizer;
        this.json = json;
    }

    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            return Long.parseLong(value);
        } catch (IllegalArgumentException invalid) { throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR); }
    }

    public static BigDecimal money(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,16})\\.[0-9]{2}")) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var amount = new BigDecimal(value);
        if (amount.signum() <= 0) throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        return amount;
    }

    public static String note(String value) {
        if (value == null) return null;
        if (value.length() > 500 || UNSAFE.matcher(value).find()) throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        return value.strip();
    }

    public record Change(long customerId, Long accountId, BigDecimal perTransaction, BigDecimal daily,
                         String currency, Instant effectiveFrom, String reason) {
        public Change {
            if (customerId <= 0 || (accountId != null && accountId <= 0) || perTransaction == null || daily == null
                    || perTransaction.signum() <= 0 || daily.signum() <= 0 || perTransaction.scale() > 2 || daily.scale() > 2
                    || perTransaction.compareTo(daily) > 0 || !"VND".equals(currency)) {
                throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
            }
            reason = note(reason);
        }

        public Map<String, Object> payload(Object current) {
            Map<String, Object> proposed = new LinkedHashMap<>();
            proposed.put("perTransactionLimit", perTransaction.setScale(2).toPlainString());
            proposed.put("dailyLimit", daily.setScale(2).toPlainString());
            proposed.put("effectiveFrom", effectiveFrom == null ? "IMMEDIATE" : effectiveFrom.toString());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("customerId", Long.toString(customerId));
            payload.put("accountId", accountId == null ? null : accountId.toString());
            payload.put("current", current);
            payload.put("proposed", proposed);
            payload.put("reason", reason);
            return payload;
        }
    }

    public ActionBinding create(long userId, Change change) {
        var payload = change.payload(null);
        payload.put("reason", change.reason() == null ? "" : change.reason());
        return new ActionBinding(userId, "LIMIT_CHANGE_CREATE", null,
                hash(Map.of("actor", Long.toString(userId), "action", "LIMIT_CHANGE_CREATE", "payload", payload)));
    }

    public ActionBinding decision(long userId, long approvalId, String decision, String note, Map<String, Object> payload) {
        return new ActionBinding(userId, "LIMIT_CHANGE_DECIDE", approvalId,
                hash(Map.of("actor", Long.toString(userId), "action", "LIMIT_CHANGE_DECIDE", "approvalId", Long.toString(approvalId),
                        "decision", decision, "note", note == null ? "" : note, "proposalHash", hash(payload))));
    }

    public String hash(Map<String, Object> value) {
        try { return HmacService.sha256Hex(canonicalizer.canonicalize(json.writeValueAsString(value))); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid binding payload"); }
    }
}
