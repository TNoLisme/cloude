package com.simulatedbank.banking.identity.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengePurpose;
import com.simulatedbank.banking.identity.api.StepUpChallengePort.ChallengeView;
import com.simulatedbank.banking.identity.application.FidoLoginService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract-level DTO behavior for API-AUTH-01..05 (07 §7.2.3/§7.2.4, §8.1.1–§8.1.5):
 * strict JSON types, canonical UUID, conditional proofs, secret-only serialization
 * and buffer erasure. Limits follow the contract (64/128/100/512, proof 6–8 digits);
 * no invented rules.
 */
class IdentityRestDtoValidationTest {
    private static final String REF = "11111111-2222-4333-8444-555555555555";
    private static ObjectMapper json;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        json = JsonMapper.builder().addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Same Instant serialization as application.yml (ISO-8601, not epoch).
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // --- API-AUTH-01 LoginRequest ---

    @Test
    void loginRequestAcceptsContractMinimum() {
        var dto = parse(LoginRequest.class,
                "{\"username\":\"Consumer01\",\"password\":\"p\",\"deviceName\":\"d\"}");
        assertThat(validator.validate(dto)).isEmpty();
        assertThat(dto.username()).isEqualTo("Consumer01");
        assertThat(dto.password()).isEqualTo("p");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65})
    void loginUsernameOutside1To64IsRejected(int length) {
        var dto = parse(LoginRequest.class,
                "{\"username\":\"" + "x".repeat(length) + "\",\"password\":\"p\"}");
        assertThat(validator.validate(dto)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 129})
    void loginPasswordOutside1To128IsRejected(int length) {
        var dto = parse(LoginRequest.class,
                "{\"username\":\"consumer01\",\"password\":\"" + "p".repeat(length) + "\"}");
        assertThat(validator.validate(dto)).isNotEmpty();
    }

    @Test
    void loginDeviceRefMustBeCanonicalUuidOrAbsent() {
        // Jackson type failures are checked IOExceptions; parse surfaces them as the root cause.
        assertThatThrownBy(() -> parse(LoginRequest.class,
                "{\"username\":\"consumer01\",\"password\":\"p\",\"deviceRef\":\"1111111-2222-4333-8444-55555555555\"}"))
                .hasRootCauseInstanceOf(MismatchedInputException.class);
        var dto = parse(LoginRequest.class,
                "{\"username\":\"consumer01\",\"password\":\"p\",\"deviceRef\":\"" + REF + "\"}");
        assertThat(dto.deviceRef()).isEqualTo(UUID.fromString(REF));
    }

    @Test
    void loginRejectsNumericPasswordAndUnknownFieldsAtTheJsonBoundary() {
        assertThatThrownBy(() -> json.readValue(
                "{\"username\":\"consumer01\",\"password\":123}", LoginRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> json.readValue(
                "{\"username\":\"consumer01\",\"password\":\"p\",\"roles\":[\"ADMIN\"]}", LoginRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    // --- API-AUTH-04 OTP request ---

    @Test
    void otpRequestWipesBufferOnEraseAndNeverPrintsProof() {
        char[] otp = "123456".toCharArray();
        var dto = new LoginOtpVerificationRequest(REF, otp);
        assertThat(dto.toString()).doesNotContain("123456");
        dto.eraseOtp();
        assertThat(dto.otp()).containsOnly('\0');
    }

    @Test
    void otpRequestRejectsCharArrayJsonRepresentation() {
        assertThatThrownBy(() -> json.readValue(
                "{\"challengeId\":\"" + REF + "\",\"otp\":[1,2,3,4,5,6]}", LoginOtpVerificationRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    // --- API-AUTH-02 passcode request ---

    @Test
    void passcodeRequestRejectsNumberPasscodeAndWipes() {
        assertThatThrownBy(() -> json.readValue(
                "{\"username\":\"consumer01\",\"passcode\":123456,\"deviceRef\":\"" + REF + "\","
                        + "\"deviceFingerprint\":\"fp\"}", PasscodeLoginRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        char[] passcode = "123456".toCharArray();
        var dto = new PasscodeLoginRequest("consumer01", passcode, REF, "fp");
        dto.erasePasscode();
        assertThat(dto.passcode()).containsOnly('\0');
        assertThat(dto.toString()).doesNotContain("123456");
    }

    // --- API-AUTH-05 step-up request ---

    @Test
    void stepUpValidationSelectsProofByMethod() {
        var otp = new StepUpVerificationRequest(REF, "OTP", "123456".toCharArray());
        otp.validate();
        assertThat(otp.toString()).doesNotContain("123456");
        var passcode = new StepUpVerificationRequest(REF, "PASSCODE", null, "123456".toCharArray(), null);
        passcode.validate();
        var fido = new StepUpVerificationRequest(REF, "FIDO", null, null, validAssertion());
        fido.validate();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"otp", "PASScode", "FIDO2", "unknown"})
    void stepUpUnknownMethodIsValidationFailure(String method) {
        var dto = new StepUpVerificationRequest(REF, method, "123456".toCharArray());
        assertThatThrownBy(dto::validate)
                .isInstanceOf(BankingBusinessException.class)
                .extracting(ex -> ((BankingBusinessException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void stepUpOtpMethodRequiresSixToEightDigits() {
        for (char[] otp : new char[][] {null, "12345".toCharArray(), "123456789".toCharArray(),
                "12345a".toCharArray(), " 123456".toCharArray()}) {
            var dto = new StepUpVerificationRequest(REF, "OTP", otp);
            assertThatThrownBy(dto::validate).isInstanceOf(BankingBusinessException.class);
        }
        for (char[] otp : new char[][] {"123456".toCharArray(), "1234567".toCharArray(),
                "12345678".toCharArray()}) {
            new StepUpVerificationRequest(REF, "OTP", otp).validate();
        }
    }

    @Test
    void stepUpPasscodeMethodRequiresPasscodeProof() {
        assertThatThrownBy(() -> new StepUpVerificationRequest(REF, "PASSCODE", "123456".toCharArray(), null, null)
                .validate()).isInstanceOf(BankingBusinessException.class);
        assertThatThrownBy(() -> new StepUpVerificationRequest(REF, "PASSCODE", null, "12345".toCharArray(), null)
                .validate()).isInstanceOf(BankingBusinessException.class);
    }

    @Test
    void stepUpFidoMethodRequiresCompleteBase64UrlAssertion() {
        assertThatThrownBy(() -> new StepUpVerificationRequest(REF, "FIDO", null, null, null)
                .validate()).isInstanceOf(BankingBusinessException.class);
        assertThatThrownBy(() -> new StepUpVerificationRequest(REF, "FIDO", null, null,
                new FidoController.Assertion("AQID", "AQID", "AQID", "", null)).validate())
                .isInstanceOf(BankingBusinessException.class);
        assertThatThrownBy(() -> new StepUpVerificationRequest(REF, "FIDO", null, null,
                new FidoController.Assertion("AQID", "AQID", "AQID", "not+base64url/", null)).validate())
                .isInstanceOf(BankingBusinessException.class);
    }

    @Test
    void stepUpChallengeIdMustBeCanonicalUuid() {
        var dto = new StepUpVerificationRequest("short-ref", "OTP", "123456".toCharArray());
        assertThatThrownBy(dto::validate).isInstanceOf(BankingBusinessException.class);
        dto.eraseSecrets();
    }

    // --- Responses (07 §7.2.3/§7.2.4) ---

    @Test
    void challengeResponseOmitsAbsentOptionalFields() throws Exception {
        var view = new ChallengeView(REF, ChallengePurpose.LOGIN_NEW_DEVICE, "OTP", null,
                Instant.parse("2026-11-16T10:05:00Z"), 5, 5);
        String body = json.writeValueAsString(ChallengeRequiredResponse.login(view, "corr"));
        assertThat(body).contains("\"code\":\"AUTH_CHALLENGE_REQUIRED\"", "\"methods\":[\"OTP\"]",
                "\"expiresAt\":\"2026-11-16T10:05:00Z\"", "\"maxAttempts\":5");
        assertThat(body).doesNotContain("maskedDestination", "fidoRequestOptions", "csrf", "token", "otpHash");
    }

    @Test
    void fidoChallengeResponseCarriesRequiredUserVerificationAndNeverSecrets() throws Exception {
        var begun = new FidoLoginService.Begun(REF, "bm90ZQ", "Y3JlZA", Instant.parse("2026-11-16T10:05:00Z"),
                5, "bank.example", 30000);
        String body = json.writeValueAsString(ChallengeRequiredResponse.fido(begun, "corr"));
        assertThat(body).contains("\"methods\":[\"FIDO\"]", "\"userVerification\":\"required\"",
                "\"rpId\":\"bank.example\"", "\"allowCredentials\":[{\"type\":\"public-key\"");
        assertThat(body).doesNotContain("privateKey", "signature", "clientData");
    }

    private static FidoController.Assertion validAssertion() {
        return new FidoController.Assertion("AQID", "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0In0", "AQID", "AQID", "dXNlcg");
    }

    private static <T> T parse(Class<T> type, String body) {
        try {
            return json.readValue(body, type);
        } catch (Exception failure) {
            // Rethrow Jackson failures unwrapped so callers can assert the contract failure type.
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(failure);
        }
    }
}
