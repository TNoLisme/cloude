package com.simulatedbank.banking.transfer.adapter.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Completed;
import com.simulatedbank.banking.transfer.application.TransferCreateOutcome.Failed;
import com.simulatedbank.banking.transfer.application.TransferFailureCode;
import com.simulatedbank.banking.transfer.domain.TransferStatus;
import com.simulatedbank.transfercandidate.web.TransferHttpConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

/** Contract suite (07 §10, CT-API-006/011/017): real responses must match the machine-readable OpenAPI. */
@SpringBootTest(properties = {
        "app.identity.enabled=false",
        "app.customer.enabled=false",
        "app.account.enabled=false",
        "app.admin.enabled=false",
        "app.audit.enabled=false",
        "app.limit.enabled=false",
        "app.fraud.enabled=false",
        "app.notification.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TransferHttpConfiguration.class, TransferControllerHttpTest.Fakes.class})
class TransferApiContractTest {
    private static final String REF = "TXN20261116A1B2C3D4";
    private static final String VALID = """
            {"sourceAccountId":"3001","destination":{"accountNumber":"0123456789"},"amount":"150000.00"}
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TransferControllerHttpTest.Fakes fakes;
    private Map<String, Object> schemas;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void loadSpec() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(
                Path.of("../digitalbanking-docs/openapi/digital-banking-v1.yaml")));
        schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        fakes.outcome.set(new Completed(TransferControllerHttpTest.view(TransferStatus.COMPLETED, null)));
        fakes.stored.set(Optional.of(TransferControllerHttpTest.view(TransferStatus.COMPLETED, null)));
    }

    @Test
    void postCompletedBodyMatchesTransferResponseSchema() throws Exception {
        var body = mapper.readTree(mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andReturn().getResponse().getContentAsString());
        validate(body, schema("TransferResponse"), true);
        assertThat(body.path("reference").asText()).matches("TXN[0-9]{8}[A-Z0-9]{8,20}");
        assertThat(body.path("amount").asText()).matches("(0|[1-9][0-9]{0,16})\\.[0-9]{2}");
        Instant.parse(body.path("createdAt").asText());
    }

    @Test
    void postFailedBodyCarriesContractOutcomeExtensions() throws Exception {
        fakes.outcome.set(new Failed(TransferControllerHttpTest.view(TransferStatus.FAILED,
                TransferFailureCode.ACCOUNT_INACTIVE)));
        var body = mapper.readTree(mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andReturn().getResponse().getContentAsString());
        validate(body, schema("Problem"), false);
        assertThat(body.path("code").asText()).isEqualTo("TRANSFER_FAILED");
        assertThat(body.path("transferStatus").asText()).isEqualTo("FAILED");
        assertThat(body.path("failureCode").asText()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(body.path("reference").asText()).isEqualTo(REF);
    }

    @Test
    void getDetailBodyMatchesTransferDetailSchemaIncludingFraudFlags() throws Exception {
        var body = mapper.readTree(mvc.perform(get("/api/v1/transfers/" + REF)
                .header("Authorization", "Bearer staff-fraud"))
                .andReturn().getResponse().getContentAsString());
        validate(body, schema("TransferDetail"), true);
        assertThat(body.path("fraudFlags").isArray()).isTrue();
        validate(body.path("fraudFlags").get(0), schema("FraudFlagSummary"), true);
        assertThat(body.path("fraudFlags").get(0).path("ruleVersion").isInt()).isTrue();
    }

    @Test
    void validationProblemMatchesProblemSchemaAndCatalog() throws Exception {
        var body = mapper.readTree(mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer customer-a")
                .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andReturn().getResponse().getContentAsString());
        validate(body, schema("Problem"), false);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("errors").isArray()).isTrue();
        assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("Idempotency-Key");
        Set<Object> catalog = new HashSet<>((List<Object>) schema("ErrorCode").get("enum"));
        assertThat(catalog).contains(body.path("code").asText());
    }

    @Test
    void transferResponseRequiredFieldsMatchContract() {
        assertThat((List<Object>) schema("TransferResponse").get("required")).containsExactlyInAnyOrder(
                "reference", "status", "amount", "currency", "sourceAccount", "destinationAccount",
                "correlationId", "createdAt");
        assertThat((List<Object>) schema("AccountRef").get("required"))
                .containsExactlyInAnyOrder("accountId", "accountNumberMasked", "accountNumberLast4");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(String name) {
        return (Map<String, Object>) schemas.get(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(Map<String, Object> schema) {
        Map<String, Object> current = schema;
        while (current.containsKey("$ref")) {
            String ref = (String) current.get("$ref");
            current = schema(ref.substring(ref.lastIndexOf('/') + 1));
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private void validate(JsonNode node, Map<String, Object> schema, boolean strict) {
        if (schema.containsKey("$ref")) {
            validate(node, resolve(schema), strict);
            return;
        }
        if (schema.containsKey("allOf")) {
            Map<String, Object> properties = new java.util.HashMap<>();
            List<Object> required = new ArrayList<>();
            for (Object sub : (List<Object>) schema.get("allOf")) {
                Map<String, Object> resolved = resolve((Map<String, Object>) sub);
                properties.putAll((Map<String, Object>) resolved.getOrDefault("properties", Map.of()));
                required.addAll((List<Object>) resolved.getOrDefault("required", List.of()));
            }
            validate(node, Map.of("type", "object", "properties", properties, "required", required), strict);
            return;
        }
        Object type = schema.get("type");
        List<String> types = type instanceof List<?> list ? list.stream().map(Object::toString).toList()
                : List.of(String.valueOf(type));
        if (node == null || node.isNull()) {
            assertThat(types).as("nullable").contains("null");
            return;
        }
        if (types.contains("object")) {
            assertThat(node.isObject()).isTrue();
            Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            for (Object required : (List<Object>) schema.getOrDefault("required", List.of())) {
                assertThat(node.has(required.toString())).as("required field " + required).isTrue();
            }
            if (strict) {
                node.fieldNames().forEachRemaining(field ->
                        assertThat(properties).as("unexpected field " + field).containsKey(field));
            }
            node.fields().forEachRemaining(entry -> {
                if (properties.containsKey(entry.getKey())) {
                    validate(entry.getValue(), (Map<String, Object>) properties.get(entry.getKey()), strict);
                }
            });
        } else if (types.contains("array")) {
            assertThat(node.isArray()).isTrue();
            Map<String, Object> items = (Map<String, Object>) schema.get("items");
            if (items != null) {
                node.forEach(item -> validate(item, items, strict));
            }
        } else if (types.contains("integer")) {
            assertThat(node.isInt()).isTrue();
        } else if (types.contains("string")) {
            assertThat(node.isTextual()).isTrue();
            if (schema.containsKey("pattern")) {
                assertThat(node.asText()).as("pattern").matches((String) schema.get("pattern"));
            }
            if (schema.containsKey("enum")) {
                assertThat((List<Object>) schema.get("enum")).contains(node.asText());
            }
            if ("date-time".equals(schema.get("format"))) {
                Instant.parse(node.asText());
            }
        }
    }
}