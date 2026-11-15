package com.simulatedbank.banking.identity.adapter.out;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class IdentityCryptoConfig {
    @Bean
    PasswordEncoder identityPasswordEncoder() {
        Map<String, PasswordEncoder> encoders = Map.of(
                "argon2", new Argon2PasswordEncoder(16, 32, 4, 65_536, 3),
                "bcrypt", new BCryptPasswordEncoder(12));
        return new DelegatingPasswordEncoder("argon2", encoders);
    }
}
