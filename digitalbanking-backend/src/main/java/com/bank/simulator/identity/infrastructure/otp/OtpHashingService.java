package com.bank.simulator.identity.infrastructure.otp;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class OtpHashingService {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public String encode(String code) {
        return encoder.encode(code);
    }

    public boolean matches(String code, String encoded) {
        return encoder.matches(code, encoded);
    }
}
