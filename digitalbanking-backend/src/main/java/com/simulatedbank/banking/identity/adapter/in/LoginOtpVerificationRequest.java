package com.simulatedbank.banking.identity.adapter.in;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Arrays;

/** API-AUTH-04 input: only the random challenge reference and its OTP, never user/device IDs. */
public record LoginOtpVerificationRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) String challengeId,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @JsonDeserialize(using = StepUpVerificationRequest.OtpDeserializer.class) char[] otp) {
    void eraseOtp() {
        if (otp != null) {
            Arrays.fill(otp, '\0');
        }
    }

    @Override
    public String toString() {
        return "LoginOtpVerificationRequest[redacted]";
    }
}
