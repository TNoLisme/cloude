package com.simulatedbank.banking.identity.application;

/**
 * Port cho băm/so khớp mật khẩu (design/04 §4.1: Argon2id là mặc định).
 * Implement phải: không log plaintext/hash, so khớp constant-time, và ném
 * {@link PasswordHashFormatException} khi stored hash sai định dạng (T08 safe failure).
 */
public interface PasswordHasher {

    /** Băm plaintext theo tham số Argon2id đã duyệt; chỉ dùng khi tạo/đổi credential. */
    String hash(char[] password);

    /**
     * So khớp plaintext với stored hash (PHC format `$argon2id$...`).
     * Trả false khi không khớp; ném {@link PasswordHashFormatException} khi hash không parse được.
     */
    boolean matches(String storedHash, char[] password);

    /**
     * Dummy hash hợp lệ Argon2id (design/04 §4.1 anti-enumeration): dùng cho unknown-user flow
     * để chạy phép verify đủ chi phí. Sinh một lần khi khởi tạo, không phải password thật,
     * không bao giờ trả về client.
     */
    String dummyHash();
}
