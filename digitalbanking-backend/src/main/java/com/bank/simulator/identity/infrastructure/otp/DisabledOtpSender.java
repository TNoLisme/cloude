package com.bank.simulator.identity.infrastructure.otp;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !demo")
public class DisabledOtpSender implements OtpSender {

    @Override
    public void send(OtpMessage message) {
        throw new IllegalStateException("No OTP sender is configured for the active profile");
    }
}
