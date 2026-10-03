package com.bank.simulator.identity.application;

import com.bank.simulator.shared.error.ApiException;
import org.springframework.http.HttpStatus;

public final class OtpRejectedException extends ApiException {

    public OtpRejectedException() {
        super(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP is invalid.");
    }
}
