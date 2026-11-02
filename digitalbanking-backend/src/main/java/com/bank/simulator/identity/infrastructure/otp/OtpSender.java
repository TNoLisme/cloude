package com.bank.simulator.identity.infrastructure.otp;

public interface OtpSender {

    void send(OtpMessage message);
}
