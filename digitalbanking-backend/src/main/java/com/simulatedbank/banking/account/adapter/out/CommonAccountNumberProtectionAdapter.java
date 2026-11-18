package com.simulatedbank.banking.account.adapter.out;

import com.simulatedbank.banking.account.application.AccountNumberProtection;
import com.simulatedbank.banking.account.domain.AccountNumber;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.crypto.PiiMaskingUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapter duy nhất của Account cho crypto/masking (BE-ACC-03), ủy quyền toàn bộ cho Common:
 * {@link PiiCryptoService} (AES-256-GCM, IV 12B mới mỗi lần) và {@link HmacService}
 * (HMAC-SHA256). Không có framework mã hóa thứ hai, không dùng SHA-256 unkeyed cho blind index
 * (BE-DAT-06/07; {@code ck_account_no_hash_hex}).
 *
 * <p>Khóa do {@code CryptoConfig} đọc từ cấu hình ngoài source; adapter không giữ/không log khóa.
 * Lỗi crypto để exception an toàn của Common lan truyền (message không chứa input) — service map
 * thành lỗi kỹ thuật, không trả plaintext.</p>
 */
@Component
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
public class CommonAccountNumberProtectionAdapter implements AccountNumberProtection {

    private final PiiCryptoService crypto;
    private final HmacService hmac;

    public CommonAccountNumberProtectionAdapter(PiiCryptoService crypto, HmacService hmac) {
        this.crypto = crypto;
        this.hmac = hmac;
    }

    @Override
    public ProtectedAccountNumber protect(String accountNumberDigits) {
        AccountNumber number = AccountNumber.parse(accountNumberDigits);
        return new ProtectedAccountNumber(
                crypto.encrypt(number.digits()),
                hmac.hmacSha256Hex(number.digits()),
                number.last4());
    }

    @Override
    public String blindIndex(String accountNumberDigits) {
        return hmac.hmacSha256Hex(AccountNumber.parse(accountNumberDigits).digits());
    }

    @Override
    public String decrypt(byte[] ciphertext) {
        return crypto.decrypt(ciphertext);
    }

    @Override
    public String mask(String accountNumberLast4) {
        return PiiMaskingUtils.maskAccountLast4(accountNumberLast4);
    }

    @Override
    public String toString() {
        return "CommonAccountNumberProtectionAdapter[redacted]";
    }
}
