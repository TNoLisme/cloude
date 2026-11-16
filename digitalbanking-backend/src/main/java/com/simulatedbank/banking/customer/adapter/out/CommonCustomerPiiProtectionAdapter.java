package com.simulatedbank.banking.customer.adapter.out;

import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.crypto.PiiMaskingUtils;
import com.simulatedbank.banking.customer.application.CustomerPiiProtection;
import org.springframework.stereotype.Component;

/** Customer adapter delegating cryptography and masking exclusively to Common primitives. */
@Component
public class CommonCustomerPiiProtectionAdapter implements CustomerPiiProtection {
    private final PiiCryptoService crypto;

    public CommonCustomerPiiProtectionAdapter(PiiCryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public byte[] encrypt(String plaintext) {
        return crypto.encrypt(plaintext);
    }

    @Override
    public String decrypt(byte[] ciphertext) {
        return crypto.decrypt(ciphertext);
    }

    @Override
    public String maskPhone(String phone) {
        return PiiMaskingUtils.maskPhone(phone);
    }

    @Override
    public String maskEmail(String email) {
        return PiiMaskingUtils.maskEmail(email);
    }

    @Override
    public String toString() {
        return "CommonCustomerPiiProtectionAdapter[redacted]";
    }
}
