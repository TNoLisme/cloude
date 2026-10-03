package com.bank.simulator.customer.infrastructure;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class AccountNumberGeneratorTest {

    @Test
    void generatesTwelveDigitNumber() {
        AccountNumberGenerator generator = new AccountNumberGenerator(new SecureRandom());

        String number = generator.generate();

        assertThat(number).matches("[0-9]{12}");
    }
}
