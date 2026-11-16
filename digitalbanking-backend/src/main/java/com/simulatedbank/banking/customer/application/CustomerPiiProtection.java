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
}
