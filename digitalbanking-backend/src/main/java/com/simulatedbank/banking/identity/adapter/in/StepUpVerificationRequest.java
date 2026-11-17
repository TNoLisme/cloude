package com.simulatedbank.banking.identity.adapter.in;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.Arrays;

/** API-AUTH-05 input; selected proof is validated before checking supported stored protocols. */
public record StepUpVerificationRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) String challengeId,
        @JsonDeserialize(using = StrictStringDeserializer.class) String method,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @JsonDeserialize(using = OtpDeserializer.class) char[] otp,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @JsonDeserialize(using = PasscodeLoginRequest.PasscodeDeserializer.class) char[] passcode,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) FidoController.Assertion fido) {

    public StepUpVerificationRequest(String challengeId, String method, char[] otp) {
        this(challengeId, method, otp, null, null);
    }

    void validate() {
        IdentityRequestValidation.requireUuid(challengeId);
        if (method == null) {
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.VALIDATION_ERROR);
        }
        switch (method) {
            case "OTP" -> IdentityRequestValidation.requireDigits(otp);
            case "PASSCODE" -> IdentityRequestValidation.requireDigits(passcode);
            case "FIDO" -> {
                if (fido == null) {
                    throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                            com.simulatedbank.banking.common.error.ErrorCode.VALIDATION_ERROR);
                }
                fido.validate();
            }
            default -> throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.VALIDATION_ERROR);
        }
    }

    void eraseSecrets() {
        if (otp != null) {
            Arrays.fill(otp, '\0');
        }
        if (passcode != null) {
            Arrays.fill(passcode, '\0');
        }
    }

    @Override
    public String toString() {
        return "StepUpVerificationRequest[redacted]";
    }

    /** Accept the contract's JSON string only, not Jackson's alternative char-array representation. */
    public static final class OtpDeserializer extends JsonDeserializer<char[]> {
        @Override
        public char[] deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(char[].class, "otp must be a string");
            }
            char[] text = parser.getTextCharacters();
            int offset = parser.getTextOffset();
            return Arrays.copyOfRange(text, offset, offset + parser.getTextLength());
        }
    }
}
