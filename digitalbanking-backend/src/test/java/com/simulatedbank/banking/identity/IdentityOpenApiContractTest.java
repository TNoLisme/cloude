package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Contract consistency between the canonical OpenAPI document and the approved
 * `07. API contracts` §8.1 + DEC-ID-006/008/009/010 (BE-ID-IMP-12). Asserts the
 * eight auth operations, their security semantics and honest runtime status;
 * structural `$ref` integrity; secret-free success schemas. Not a source-text
 * snapshot: keys are parsed, never matched as wording.
 */
class IdentityOpenApiContractTest {
    private static final Path DOCUMENT = Path.of("..", "digitalbanking-docs", "openapi",
            "digital-banking-v1.yaml");
    private static Map<String, Object> doc;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() {
        assertThat(DOCUMENT).as("canonical OpenAPI document").exists();
        try {
            doc = new Yaml().load(Files.readString(DOCUMENT));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void allEightAuthOperationsExistWithExactMethodsAndPaths() {
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Map<String, String> expected = Map.of(
                "/auth/login", "post",
                "/auth/passcode", "post",
                "/auth/fido/verify", "post",
                "/auth/otp/verify", "post",
                "/auth/step-up/verify", "post",
                "/auth/logout", "post",
                "/auth/session", "get",
                "/auth/challenges/{challengeId}/resend", "post");
        expected.forEach((path, method) -> {
            Map<String, Object> item = (Map<String, Object>) paths.get(path);
            assertThat(item).as("path " + path).isNotNull();
            Map<String, Object> operation = (Map<String, Object>) item.get(method);
            assertThat(operation).as("operation %s %s", method, path).isNotNull();
            assertThat(operation).containsKey("operationId");
            assertThat(operation).containsKey("responses");
        });
        assertThat(paths.keySet()).containsAll(expected.keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void securitySemanticsMatchTheApprovedContract() {
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        // Public proof endpoints advertise empty security.
        for (String path : List.of("/auth/login", "/auth/passcode",
                "/auth/fido/verify", "/auth/otp/verify")) {
            Map<String, Object> operation = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("post");
            assertThat((List<?>) operation.get("security")).as("public " + path).isEmpty();
        }
        // Session-bearing endpoints offer cookie and bearer alternatives.
        for (String path : List.of("/auth/step-up/verify", "/auth/logout",
                "/auth/session")) {
            Object method = path.endsWith("/session") ? "get" : "post";
            Map<String, Object> operation = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get(method);
            List<Map<String, Map<String, Object>>> security =
                    (List<Map<String, Map<String, Object>>>) operation.get("security");
            assertThat(security).as("authenticated " + path).isNotEmpty();
            security.forEach(entry -> assertThat(entry.keySet())
                    .as("schemes for " + path).containsAnyOf("sessionCookie", "bearerAuth"));
        }
        // Resend is purpose-dependent: public with challenge proof OR authenticated owner.
        Map<String, Object> resend = (Map<String, Object>) ((Map<String, Object>)
                paths.get("/auth/challenges/{challengeId}/resend")).get("post");
        List<Map<String, Map<String, Object>>> security =
                (List<Map<String, Map<String, Object>>>) resend.get("security");
        assertThat(security).contains(Map.of());
        assertThat(security).anySatisfy(entry ->
                assertThat(entry.keySet()).containsAnyOf("sessionCookie", "bearerAuth"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeStatusIsHonestAboutApprovedBlockers() {
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Map<String, Object> passcode = (Map<String, Object>) ((Map<String, Object>)
                paths.get("/auth/passcode")).get("post");
        assertThat(passcode).containsEntry("x-runtime-status", "blocked-positive");
        Map<String, Object> resend = (Map<String, Object>) ((Map<String, Object>)
                paths.get("/auth/challenges/{challengeId}/resend")).get("post");
        assertThat(resend).containsEntry("x-runtime-status", "sms-gateway-required").doesNotContainKey("requestBody");
        Map<String, Object> login = (Map<String, Object>) ((Map<String, Object>)
                paths.get("/auth/login")).get("post");
        String loginDescription = String.valueOf(login.get("description"));
        assertThat(loginDescription).contains("202", "OTP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fidoOptionsRequireUserVerificationNotTheRetiredExample() {
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
        Map<String, Object> options = (Map<String, Object>) schemas.get("FidoRequestOptions");
        assertThat(options).as("FidoRequestOptions schema").isNotNull();
        Map<String, Object> properties = (Map<String, Object>) options.get("properties");
        Map<String, Object> verification = (Map<String, Object>) properties.get("userVerification");
        assertThat(verification).isNotNull();
        if (verification.containsKey("enum")) {
            assertThat((List<Object>) verification.get("enum")).containsExactly("required");
        } else {
            assertThat(String.valueOf(verification.getOrDefault("const", ""))).isEqualTo("required");
        }
        Map<String, Object> fidoOperation = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) doc.get("paths")).get("/auth/fido/verify")).get("post");
        assertThat(String.valueOf(fidoOperation.get("description"))).contains("user verification");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyLocalRefTargetResolvesInsideTheDocument() {
        List<String> broken = new java.util.ArrayList<>();
        collectRefs(doc, "", broken);
        assertThat(broken).as("unresolved $ref targets").isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void successSchemasNeverExposeCredentialOrSecretFields() {
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
        for (String name : List.of("AuthResponse", "ChallengeRequiredResponse", "StepUpVerifyResponse")) {
            Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
            assertThat(schema).as("schema " + name).isNotNull();
            String serialized = String.valueOf(schema);
            Set<String> forbidden = Set.of("password", "otp\"", "passcode", "sessionToken", "session_token_hash",
                    "csrf_token_hash", "otp_hash");
            for (String term : forbidden) {
                assertThat(serialized.toLowerCase()).as(name + " leaks " + term).doesNotContain(term.toLowerCase());
            }
        }
        Map<String, Object> challenge = (Map<String, Object>) schemas.get("ChallengeRequiredResponse");
        // Challenge payload carries identifiers and TTL metadata only; a raw session cookie token is forbidden.
        assertThat(String.valueOf(challenge).toLowerCase()).doesNotContain("db_session");
    }

    @SuppressWarnings("unchecked")
    private static void collectRefs(Object node, String path, List<String> broken) {
        if (node instanceof Map<?, ?> map) {
            Object ref = map.get("$ref");
            if (ref instanceof String value && value.startsWith("#/")) {
                Object cursor = doc;
                for (String segment : value.substring(2).split("/")) {
                    if (!(cursor instanceof Map<?, ?> container)) {
                        broken.add(value + " (at " + path + ")");
                        return;
                    }
                    cursor = container.get(segment.replace("~1", "/").replace("~0", "~"));
                }
            }
            map.forEach((key, value) -> collectRefs(value, path + "/" + key, broken));
        } else if (node instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                collectRefs(list.get(index), path + "/" + index, broken);
            }
        }
    }
}
