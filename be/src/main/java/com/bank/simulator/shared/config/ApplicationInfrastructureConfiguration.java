package com.bank.simulator.shared.config;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

@Configuration
public class ApplicationInfrastructureConfiguration {

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    AccountNumberGenerator accountNumberGenerator(SecureRandom secureRandom) {
        return new AccountNumberGenerator(secureRandom);
    }
}
