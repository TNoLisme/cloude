package com.simulatedbank.banking.identity.adapter.out;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.application.FidoProperties;
import com.simulatedbank.banking.identity.application.port.FidoAssertionVerifier;
import com.simulatedbank.banking.identity.application.port.FidoCredentialStore.Credential;
import com.simulatedbank.banking.identity.application.port.FidoVerificationException;
import com.simulatedbank.banking.identity.application.port.FidoVerificationException.Reason;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.COSEAlgorithmIdentifier;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.InvalidSignatureCountException;
import com.yubico.webauthn.data.exception.Base64UrlException;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Signature, RP hash, challenge, origin, UP/UV, ownership and counter verification use Yubico. */
@Component
@ConditionalOnProperty(name = "app.identity.fido.enabled", havingValue = "true")
public final class YubicoFidoAssertionVerifier implements FidoAssertionVerifier {
    // SHA-1 and algorithms not supported by the Java 21 runtime are intentionally excluded.
    private static final Set<COSEAlgorithmIdentifier> ALGORITHMS = EnumSet.of(
            COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.ES384, COSEAlgorithmIdentifier.ES512,
            COSEAlgorithmIdentifier.RS256, COSEAlgorithmIdentifier.RS384, COSEAlgorithmIdentifier.RS512,
            COSEAlgorithmIdentifier.EdDSA, COSEAlgorithmIdentifier.Ed25519, COSEAlgorithmIdentifier.Ed448);
    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();

    private final FidoProperties properties;
    private final ObjectMapper clientDataMapper;
    private final RelyingPartyIdentity identity;

    public YubicoFidoAssertionVerifier(FidoProperties properties, ObjectMapper mapper, Environment environment) {
        if (!properties.enabled() || !properties.isConfigurationValid()) {
            throw new IllegalArgumentException("FIDO configuration is not enabled or valid");
        }
        if (properties.allowedOrigins().stream().anyMatch(origin -> origin.startsWith("http://"))
                && (!environment.acceptsProfiles(Profiles.of("local", "test"))
                    || java.util.Arrays.stream(environment.getActiveProfiles())
                            .anyMatch(profile -> !"local".equals(profile) && !"test".equals(profile)))) {
            throw new IllegalArgumentException("HTTP localhost FIDO origins require an active local or test profile");
        }
        this.properties = properties;
        this.clientDataMapper = mapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.identity = RelyingPartyIdentity.builder().id(properties.rpId()).name("Digital Banking").build();
    }

    @Override
    public Verified verify(Credential credential, String nonce, Assertion assertion) {
        try {
            ByteArray credentialId = decode(credential.credentialId());
            ByteArray userHandle = decode(credential.userHandle());
            if (credentialId.isEmpty() || userHandle.isEmpty() || userHandle.size() > 64
                    || credential.signCount() < 0 || credential.signCount() > 0xffff_ffffL) {
                throw new FidoVerificationException(Reason.INVALID_ASSERTION);
            }
            ByteArray publicKey = decode(credential.publicKeyCose());
            COSEAlgorithmIdentifier algorithm = COSEAlgorithmIdentifier.fromPublicKey(publicKey)
                    .orElseThrow(() -> new FidoVerificationException(Reason.UNSUPPORTED_ALGORITHM));
            if (!ALGORITHMS.contains(algorithm)) {
                throw new FidoVerificationException(Reason.UNSUPPORTED_ALGORITHM);
            }
            ByteArray clientData = decode(assertion.clientDataJSON());
            requireSameOrigin(clientData);
            var response = AuthenticatorAssertionResponse.builder()
                    .authenticatorData(decode(assertion.authenticatorData()))
                    .clientDataJSON(clientData)
                    .signature(decode(assertion.signature()))
                    .userHandle(assertion.userHandle() == null ? Optional.empty()
                            : Optional.of(decode(assertion.userHandle())))
                    .build();
            var registration = RegisteredCredential.builder()
                    .credentialId(credentialId).userHandle(userHandle).publicKeyCose(publicKey)
                    .signatureCount(credential.signCount()).build();
            String username = Long.toString(credential.userId());
            var repository = new BoundCredentialRepository(username, registration);
            var relyingParty = RelyingParty.builder().identity(identity)
                    .credentialRepository(repository).origins(properties.allowedOrigins())
                    .allowOriginPort(false).allowOriginSubdomain(false).validateSignatureCounter(true).build();
            var request = AssertionRequest.builder().publicKeyCredentialRequestOptions(
                    PublicKeyCredentialRequestOptions.builder().challenge(decode(nonce))
                            .rpId(properties.rpId()).userVerification(UserVerificationRequirement.REQUIRED)
                            .allowCredentials(List.of(repository.descriptor())).build())
                    .username(username).build();
            var publicKeyCredential = PublicKeyCredential
                    .<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs>builder()
                    .id(decode(assertion.credentialId())).response(response)
                    .clientExtensionResults(ClientAssertionExtensionOutputs.builder().build()).build();
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request).response(publicKeyCredential).build());
            if (!result.isSuccess()) {
                throw new FidoVerificationException(Reason.INVALID_ASSERTION);
            }
            return new Verified(result.getSignatureCount());
        } catch (FidoVerificationException exception) {
            throw exception;
        } catch (InvalidSignatureCountException exception) {
            throw new FidoVerificationException(Reason.COUNTER_ANOMALY);
        } catch (AssertionFailedException | IOException | Base64UrlException | RuntimeException exception) {
            // Provider messages can include credential IDs and keys. Never retain or log the cause.
            throw new FidoVerificationException(Reason.INVALID_ASSERTION);
        }
    }

    private static ByteArray decode(String value) {
        if (value == null || value.isEmpty()) {
            throw new FidoVerificationException(Reason.INVALID_ASSERTION);
        }
        ByteArray decoded = new ByteArray(BASE64URL.decode(value));
        if (!decoded.getBase64Url().equals(value)) {
            throw new FidoVerificationException(Reason.INVALID_ASSERTION);
        }
        return decoded;
    }

    private void requireSameOrigin(ByteArray clientData) throws IOException {
        JsonNode data = clientDataMapper.readTree(clientData.getBytes());
        JsonNode crossOrigin = data == null ? null : data.get("crossOrigin");
        // The WebAuthn default when omitted is false; any explicitly supplied value must be false.
        if (data == null || !data.isObject()
                || (crossOrigin != null && (!crossOrigin.isBoolean() || crossOrigin.booleanValue()))) {
            throw new FidoVerificationException(Reason.INVALID_ASSERTION);
        }
    }

    /** Only the locked credential and its owning user are visible to the library, never a global lookup. */
    private record BoundCredentialRepository(String username, RegisteredCredential credential)
            implements CredentialRepository {
        PublicKeyCredentialDescriptor descriptor() {
            return PublicKeyCredentialDescriptor.builder().id(credential.getCredentialId()).build();
        }

        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String value) {
            return username.equals(value) ? Set.of(descriptor()) : Set.of();
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String value) {
            return username.equals(value) ? Optional.of(credential.getUserHandle()) : Optional.empty();
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray value) {
            return credential.getUserHandle().equals(value) ? Optional.of(username) : Optional.empty();
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray id, ByteArray handle) {
            return credential.getCredentialId().equals(id) && credential.getUserHandle().equals(handle)
                    ? Optional.of(credential) : Optional.empty();
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray id) {
            return credential.getCredentialId().equals(id) ? Set.of(credential) : Set.of();
        }
    }
}
