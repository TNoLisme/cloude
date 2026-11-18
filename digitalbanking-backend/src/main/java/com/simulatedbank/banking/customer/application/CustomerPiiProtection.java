package com.simulatedbank.banking.customer.application;

/**
 * Customer-owned protection boundary. Implementations must fail closed and must never return
 * plaintext when encryption, authentication, or masking fails.
 */
public interface CustomerPiiProtection {

    byte[] encrypt(String plaintext);

    String decrypt(byte[] ciphertext);

    String maskPhone(String phone);

    String maskEmail(String email);

    /**
     * Staff-facing fullName masking (BE-CUS-IMP-06, DEC-CUS-005): chỉ lộ ký tự đầu mỗi token,
     * phần còn lại thay bằng `***`; token một ký tự che toàn bộ. Fail-closed: input sai thì ném
     * lỗi, không bao giờ trả nguyên văn hay một phần dài hơn quy tắc.
     */
    String maskFullName(String fullName);
}
