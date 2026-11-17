package com.simulatedbank.banking.identity.adapter.out;

import com.simulatedbank.banking.identity.application.OtpCodeGenerator;
import java.security.SecureRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * CSPRNG OTP generator (BE-ID-IMP-05): 6 chữ số (07 §3.7 — cấu hình 6–8 là đề xuất; MVP cố định 6,
 * không thêm config ngoài `AppProperties.Otp` đã duyệt). `SecureRandom` non-blocking mặc định JVM;
 * caller hash ngay rồi wipe `char[]` — plaintext không bao giờ log/persist (NFR-016).
 */
@Component
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class SecureRandomOtpCodeGenerator implements OtpCodeGenerator {

    static final int OTP_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();

    @Override
    public char[] generate() {
        char[] digits = new char[OTP_LENGTH];
        for (int i = 0; i < OTP_LENGTH; i++) {
            digits[i] = (char) ('0' + random.nextInt(10));
        }
        return digits;
    }
}
