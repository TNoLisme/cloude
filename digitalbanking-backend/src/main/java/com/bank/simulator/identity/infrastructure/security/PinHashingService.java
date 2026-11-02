package com.bank.simulator.identity.infrastructure.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PinHashingService {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public String encode(String pin) {
        return encoder.encode(pin);
    }

    public boolean matches(String pin, String encodedPin) {
        return encoder.matches(pin, encodedPin);
    }
}
