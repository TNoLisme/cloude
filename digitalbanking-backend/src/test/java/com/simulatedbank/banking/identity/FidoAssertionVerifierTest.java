package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.adapter.out.YubicoFidoAssertionVerifier;
import com.simulatedbank.banking.identity.application.FidoProperties;
import com.simulatedbank.banking.identity.application.port.FidoAssertionVerifier.Assertion;
import com.simulatedbank.banking.identity.application.port.FidoCredentialStore.Credential;
import com.simulatedbank.banking.identity.application.port.FidoVerificationException;
import com.simulatedbank.banking.identity.application.port.FidoVerificationException.Reason;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class FidoAssertionVerifierTest {
    private static final String RP_ID = "bank.example";
    private static final String ORIGIN = "https://bank.example";
    private static final String NONCE = encode("random-server-ceremony-nonce-32-bytes");
    private final FidoTestAuthenticator authenticator = FidoTestAuthenticator.create();
    private final YubicoFidoAssertionVerifier verifier = verifier(Set.of(ORIGIN));

    @ParameterizedTest
    @ValueSource(strings = {"ES256", "ES384", "ES512", "RS256", "RS384", "RS512", "EdDSA", "Ed25519", "Ed448"})
    void verifiesRealSignaturesForEverySupportedAlgorithm(String algorithm) {
        var source = FidoTestAuthenticator.create(algorithm);
        assertThat(verifier.verify(source.credential(11, 22, "device", 4), NONCE,
                source.sign(NONCE, RP_ID, ORIGIN, 5)).signCount()).isEqualTo(5);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "0, 1", "1, 2", "4294967294, 4294967295"})
    void acceptsZeroCounterPairOrStrictIncrease(long stored, long received) {
        assertThat(verifier.verify(credential(stored), NONCE,
                authenticator.sign(NONCE, RP_ID, ORIGIN, received)).signCount()).isEqualTo(received);
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "1, 1", "5, 4", "4294967295, 0", "4294967295, 4294967295"})
    void rejectsCounterRollbackEqualityAndWraparound(long stored, long received) {
        reject(credential(stored), authenticator.sign(NONCE, RP_ID, ORIGIN, received), Reason.COUNTER_ANOMALY);
    }

    @Test
    void zeroCounterDoesNotBypassSignatureVerification() {
        Assertion wrongKey = FidoTestAuthenticator.create().sign(NONCE, RP_ID, ORIGIN, 0);
        reject(credential(0), new Assertion(authenticator.credentialId(), wrongKey.clientDataJSON(),
                wrongKey.authenticatorData(), wrongKey.signature(), authenticator.userHandle()), Reason.INVALID_ASSERTION);
    }

    @Test
    void verifiesSignatureBeforeClassifyingCounterAnomaly() {
        Assertion valid = authenticator.sign(NONCE, RP_ID, ORIGIN, 4);
        reject(credential(4), new Assertion(valid.credentialId(), valid.clientDataJSON(),
                valid.authenticatorData(), mutate(valid.signature()), valid.userHandle()), Reason.INVALID_ASSERTION);
    }

    @Test
    void rejectsDifferentChallengeEvenWhenCorrectlySigned() {
        reject(authenticator.sign(encode("another-server-nonce"), RP_ID, ORIGIN, 1));
    }

    @Test
    void rejectsDifferentRpHashEvenWhenCorrectlySigned() {
        reject(authenticator.sign(NONCE, "attacker.example", ORIGIN, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://attacker.example", "https://child.bank.example", "https://bank.example:8443",
            "http://bank.example", "https://bank.example/", "https://bank.example.evil", "null"})
    void requiresExactOriginIncludingSchemeHostAndPort(String origin) {
        reject(authenticator.sign(NONCE, RP_ID, origin, 1));
    }

    @Test
    void acceptsEachExplicitlyConfiguredOriginButNotOtherPorts() {
        var configured = verifier(Set.of(ORIGIN, "https://login.bank.example:8443"));
        Assertion assertion = authenticator.sign(NONCE, RP_ID, "https://login.bank.example:8443", 1);
        assertThat(configured.verify(credential(0), NONCE, assertion).signCount()).isEqualTo(1);
        assertThatThrownBy(() -> configured.verify(credential(0), NONCE,
                authenticator.sign(NONCE, RP_ID, "https://login.bank.example:443", 1)))
                .isInstanceOf(FidoVerificationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4})
    void requiresBothUserPresenceAndUserVerification(int flags) {
        reject(authenticator.sign(NONCE, RP_ID, ORIGIN, 1, flags, "webauthn.get", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"webauthn.create", "payment.get", "webauthn.GET", ""})
    void requiresAuthenticationClientDataType(String type) {
        reject(authenticator.sign(NONCE, RP_ID, ORIGIN, 1, 5, type, false));
    }

    @Test
    void rejectsCrossOriginEvenWhenCorrectlySigned() {
        reject(authenticator.sign(NONCE, RP_ID, ORIGIN, 1, 5, "webauthn.get", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "\"false\"", "[]", "{}"})
    void rejectsNonBooleanCrossOriginValues(String crossOrigin) {
        reject(authenticator.signClientData(clientData() + ",\"crossOrigin\":" + crossOrigin + "}", RP_ID, 1, 5));
    }

    @Test
    void omittedCrossOriginUsesWebAuthnFalseDefault() {
        assertThat(verifier.verify(credential(0), NONCE,
                authenticator.signClientData(clientData() + "}", RP_ID, 1, 5)).signCount()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateClientDataMembersAndTrailingJson() {
        reject(authenticator.signClientData(clientData() + ",\"crossOrigin\":true,\"crossOrigin\":false}", RP_ID, 1, 5));
        reject(authenticator.signClientData(clientData() + ",\"type\":\"webauthn.get\"}", RP_ID, 1, 5));
        reject(authenticator.signClientData(clientData() + "} {}", RP_ID, 1, 5));
    }

    @Test
    void rejectsDifferentCredentialAndDifferentUserHandle() {
        Assertion valid = authenticator.sign(NONCE, RP_ID, ORIGIN, 1);
        var other = FidoTestAuthenticator.create();
        reject(new Assertion(other.credentialId(), valid.clientDataJSON(), valid.authenticatorData(),
                valid.signature(), valid.userHandle()));
        reject(new Assertion(valid.credentialId(), valid.clientDataJSON(), valid.authenticatorData(),
                valid.signature(), other.userHandle()));
    }

    @Test
    void absentHandleStillBindsAssertionToRequestedUsersCredential() {
        Assertion valid = authenticator.sign(NONCE, RP_ID, ORIGIN, 1);
        Assertion withoutHandle = new Assertion(valid.credentialId(), valid.clientDataJSON(),
                valid.authenticatorData(), valid.signature(), null);
        assertThat(verifier.verify(credential(0), NONCE, withoutHandle).signCount()).isEqualTo(1);
        var other = FidoTestAuthenticator.create();
        reject(other.credential(12, 23, "other-device", 0), withoutHandle, Reason.INVALID_ASSERTION);
    }

    @Test
    void rejectsTamperingWithEverySignedComponent() {
        Assertion valid = authenticator.sign(NONCE, RP_ID, ORIGIN, 1);
        reject(new Assertion(valid.credentialId(), mutate(valid.clientDataJSON()), valid.authenticatorData(),
                valid.signature(), valid.userHandle()));
        reject(new Assertion(valid.credentialId(), valid.clientDataJSON(), mutate(valid.authenticatorData()),
                valid.signature(), valid.userHandle()));
        reject(new Assertion(valid.credentialId(), valid.clientDataJSON(), valid.authenticatorData(),
                mutate(valid.signature()), valid.userHandle()));
    }

    @ParameterizedTest
    @ValueSource(ints = {-65535, -65537, -999, -50, -49, -48})
    void refusesSha1UnknownAndUnsupportedRuntimeAlgorithms(int algorithm) {
        Credential original = credential(0);
        Credential changed = withKey(original, authenticator.publicKeyCose(algorithm));
        reject(changed, authenticator.sign(NONCE, RP_ID, ORIGIN, 1), Reason.UNSUPPORTED_ALGORITHM);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not+a+url", "AA", "oA", "", "***"})
    void malformedOrMissingCoseKeyFailsClosed(String key) {
        reject(withKey(credential(0), key), authenticator.sign(NONCE, RP_ID, ORIGIN, 1), Reason.INVALID_ASSERTION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "***", "YWJj=", "Zh"})
    void rejectsMalformedOrNonCanonicalBase64WithoutProviderLeak(String malformed) {
        Assertion valid = authenticator.sign(NONCE, RP_ID, ORIGIN, 1);
        reject(new Assertion(valid.credentialId(), malformed, valid.authenticatorData(), valid.signature(), valid.userHandle()));
        reject(new Assertion(valid.credentialId(), valid.clientDataJSON(), malformed, valid.signature(), valid.userHandle()));
        reject(new Assertion(valid.credentialId(), valid.clientDataJSON(), valid.authenticatorData(), malformed, valid.userHandle()));
    }

    @Test
    void rejectsNullsMissingKeyAndInvalidStoredCountersWithoutProviderLeak() {
        reject(credential(0), null, Reason.INVALID_ASSERTION);
        reject(null, authenticator.sign(NONCE, RP_ID, ORIGIN, 1), Reason.INVALID_ASSERTION);
        reject(withKey(credential(0), null), authenticator.sign(NONCE, RP_ID, ORIGIN, 1), Reason.INVALID_ASSERTION);
        reject(credential(-1), authenticator.sign(NONCE, RP_ID, ORIGIN, 1), Reason.INVALID_ASSERTION);
        reject(credential(4294967296L), authenticator.sign(NONCE, RP_ID, ORIGIN, 1), Reason.INVALID_ASSERTION);
    }

    @Test
    void assertionToStringDoesNotExposeAnyMaterial() {
        assertThat(authenticator.sign(NONCE, RP_ID, ORIGIN, 1).toString()).isEqualTo("Assertion[REDACTED]");
    }

    private Credential credential(long count) {
        return authenticator.credential(11, 22, "device", count);
    }

    private static Credential withKey(Credential original, String key) {
        return new Credential(original.deviceId(), original.userId(), original.deviceRef(), original.credentialId(),
                key, original.userHandle(), original.signCount(), original.trusted(), original.status());
    }

    private void reject(Assertion assertion) {
        reject(credential(0), assertion, Reason.INVALID_ASSERTION);
    }

    private void reject(Credential credential, Assertion assertion, Reason reason) {
        assertThatThrownBy(() -> verifier.verify(credential, NONCE, assertion))
                .isInstanceOfSatisfying(FidoVerificationException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(reason);
                    assertThat(exception).hasMessage("FIDO verification failed").hasNoCause();
                    assertThat(exception.getSuppressed()).isEmpty();
                });
    }

    private static YubicoFidoAssertionVerifier verifier(Set<String> origins) {
        return new YubicoFidoAssertionVerifier(new FidoProperties(true, RP_ID, origins, Duration.ofSeconds(30)),
                new ObjectMapper(), new MockEnvironment());
    }

    private static String clientData() {
        return "{\"type\":\"webauthn.get\",\"challenge\":\"" + NONCE + "\",\"origin\":\"" + ORIGIN + "\"";
    }

    private static String mutate(String encoded) {
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        bytes[bytes.length - 1] ^= 1;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
