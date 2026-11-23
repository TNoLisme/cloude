package com.simulatedbank.banking.account.application;

import java.util.regex.Pattern;

/**
 * Ranh giới mã hóa/blind index/masking của Account (BE-ACC-03).
 *
 * <p>Implementation bắt buộc dùng đúng hạ tầng Common đã có — {@code PiiCryptoService}
 * (AES-256-GCM) và {@code HmacService} (HMAC-SHA256) — không mở framework mã hóa thứ hai, không
 * dùng unkeyed hash thay blind index (BE-DAT-06/07). Khóa nằm ngoài source (env/config, xem
 * {@code CryptoConfig}); port không nhận/không trả khóa.</p>
 *
 * <p>Fail closed: mọi lỗi crypto/masking phải ném exception an toàn (không chứa plaintext,
 * ciphertext, khóa); caller map thành lỗi kỹ thuật, không bao giờ trả dữ liệu thô.</p>
 */
public interface AccountNumberProtection {

    /**
     * Mã hóa số tài khoản và sinh các giá trị dẫn xuất phục vụ persistence.
     *
     * @throws IllegalArgumentException khi input không khớp {@code ^[0-9]{6,20}$}
     */
    ProtectedAccountNumber protect(String accountNumberDigits);

    /** HMAC-SHA256 lowercase hex của đúng chuỗi chữ số đã normalize (deterministic). */
    String blindIndex(String accountNumberDigits);

    /**
     * Giải mã ciphertext {@code account_no_enc}. Read contract {@code account.api} KHÔNG dùng
     * operation này (không trả plaintext); chỉ dùng cho provisioning/verify khi policy được duyệt
     * (ACC-DR-04) và trong test round-trip.
     *
     * @throws IllegalArgumentException khi ciphertext hỏng/không qua xác thực GCM
     */
    String decrypt(byte[] ciphertext);

    /** Mask hiển thị `**** **** <last4>` (OpenAPI {@code AccountNumberMasked}). */
    String mask(String accountNumberLast4);

    /**
     * Kết quả bảo vệ một số tài khoản: ciphertext, blind index và last4 được sinh trong cùng một
     * lần nên không thể lệch nhau.
     */
    record ProtectedAccountNumber(byte[] encrypted, String blindIndex, String accountNumberLast4) {

        private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
        private static final Pattern LAST4 = Pattern.compile("^[0-9]{4}$");

        public ProtectedAccountNumber {
            if (encrypted == null || encrypted.length < 28) {
                throw new IllegalArgumentException("encrypted account number is invalid");
            }
            if (blindIndex == null || !HASH.matcher(blindIndex).matches()) {
                throw new IllegalArgumentException("account number blind index must be lowercase hex 64");
            }
            if (accountNumberLast4 == null || !LAST4.matcher(accountNumberLast4).matches()) {
                throw new IllegalArgumentException("account number last4 must be 4 digits");
            }
            encrypted = encrypted.clone();
        }

        @Override
        public byte[] encrypted() {
            return encrypted.clone();
        }

        @Override
        public String toString() {
            return "ProtectedAccountNumber[redacted]";
        }
    }
}
