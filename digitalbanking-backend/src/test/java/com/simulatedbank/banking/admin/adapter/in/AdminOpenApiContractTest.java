package com.simulatedbank.banking.admin.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.admin.domain.ApprovalDecision;
import com.simulatedbank.banking.admin.domain.ApprovalRequestType;
import com.simulatedbank.banking.admin.domain.ApprovalStatus;
import com.simulatedbank.banking.admin.domain.ApprovalTargetType;
import com.simulatedbank.banking.account.adapter.in.AdminCustomerAccountsController;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.adapter.in.CustomerAdminDetailController;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.yaml.snakeyaml.Yaml;

/**
 * BE-ADM-TST-04 §4: contract drift between the canonical OpenAPI document and the implemented Admin surface.
 *
 * <p>The document is parsed (never matched as source text) and compared with the actual controller classes and
 * request DTOs of {@code /api/v1/admin/**}: path + method + path-parameter names, required write headers, request
 * schema fields and enum values, authenticated security inheritance, documented status sets, and structural
 * {@code $ref} integrity of the error/response components. Runtime behaviour (status codes, envelopes, CSRF,
 * headers) is proven separately by {@code AdminHttpContractPostgreSqlIntegrationTest}; this class fails when the
 * document and the code drift apart in either direction.</p>
 */
class AdminOpenApiContractTest {

    private static final Path DOCUMENT = Path.of("..", "digitalbanking-docs", "openapi",
            "digital-banking-v1.yaml");

    /** One documented Admin operation and the controller that must implement it. */
    private static final List<Operation> OPERATIONS = List.of(
            new Operation("/admin/roles", "post", "createRoleChange", RoleAdminController.class, "create"),
            new Operation("/admin/roles/{id}/approve", "post", "approveRoleChange", RoleAdminController.class,
                    "decide"),
            new Operation("/admin/limits", "post", "createLimitChange", LimitAdminController.class, "create"),
            new Operation("/admin/limits/{id}/approve", "post", "approveLimitChange", LimitAdminController.class,
                    "decide"),
            new Operation("/admin/approvals", "get", "listApprovals", ApprovalAdminController.class, "list"),
            new Operation("/admin/approvals/{approvalId}", "get", "getApproval", ApprovalAdminController.class,
                    "detail"),
            new Operation("/admin/customers/{customerId}", "get", "getAdminCustomer",
                    CustomerAdminDetailController.class, "detail"),
            new Operation("/admin/customers/{customerId}/accounts", "get", "getAdminCustomerAccounts",
                    AdminCustomerAccountsController.class, "list"));

    /** Statuses the document must declare per operation (07 §8.8, design/04 §5.1). */
    private static final Map<String, Set<Integer>> DOCUMENTED_STATUSES = Map.of(
            "createRoleChange", Set.of(201, 400, 401, 403, 404, 409, 428, 429, 500, 503),
            "approveRoleChange", Set.of(200, 400, 401, 403, 404, 409, 428, 429, 500, 503),
            "createLimitChange", Set.of(201, 400, 401, 403, 404, 409, 422, 428, 429, 500, 503),
            "approveLimitChange", Set.of(200, 400, 401, 403, 404, 409, 422, 428, 429, 500, 503),
            "listApprovals", Set.of(200, 400, 401, 403, 500, 503),
            "getApproval", Set.of(200, 400, 401, 403, 404, 500, 503),
            "getAdminCustomer", Set.of(200, 400, 401, 403, 404, 500, 503),
            "getAdminCustomerAccounts", Set.of(200, 400, 401, 403, 404, 500, 503));

    private static Map<String, Object> document;
    private static Map<String, Object> paths;

    record Operation(String path, String method, String operationId, Class<?> controller, String handler) {
    }

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() {
        assertThat(DOCUMENT).as("canonical OpenAPI document").exists();
        try {
            document = new Yaml().load(Files.readString(DOCUMENT));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        paths = (Map<String, Object>) document.get("paths");
    }

    @Test
    void documentedAdminOperationsMatchTheImplementedControllersExactly() {
        Set<String> documented = new TreeSet<>();
        for (Operation operation : OPERATIONS) {
            Map<String, Object> item = map(paths.get(operation.path()));
            assertThat(item).as("documented path " + operation.path).isNotNull();
            Map<String, Object> definition = map(item.get(operation.method()));
            assertThat(definition).as("%s %s operation", operation.method(), operation.path).isNotNull();
            assertThat(definition.get("operationId")).isEqualTo(operation.operationId);
            documented.add(operation.method().toUpperCase(Locale.ROOT) + " " + normalize(operation.path()));

            Method handler = Arrays.stream(operation.controller().getDeclaredMethods())
                    .filter(method -> method.getName().equals(operation.handler()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing handler " + operation.controller().getSimpleName()
                            + "#" + operation.handler()));
            RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(operation.controller(),
                    RequestMapping.class);
            assertThat(typeMapping).as("class mapping of " + operation.controller().getSimpleName()).isNotNull();
            String classPath = typeMapping.value()[0];
            String implemented = classPath + methodPath(handler);

            assertThat(normalize(implemented))
                    .as("%s#%s must serve %s", operation.controller().getSimpleName(), operation.handler(),
                            operation.path())
                    .isEqualTo(normalize("/api/v1" + operation.path()));
            assertThat(AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class).method())
                    .as("%s#%s HTTP method", operation.controller().getSimpleName(), operation.handler())
                    .containsExactly(RequestMethod.valueOf(operation.method().toUpperCase(Locale.ROOT)));
        }

        // No other Admin operation may be implemented without being documented.
        Set<String> implemented = new LinkedHashSet<>();
        for (Class<?> controller : List.of(RoleAdminController.class, LimitAdminController.class,
                ApprovalAdminController.class, CustomerAdminDetailController.class,
                AdminCustomerAccountsController.class)) {
            RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            assertThat(typeMapping).as("class mapping of " + controller.getSimpleName()).isNotNull();
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (RequestMethod httpMethod : mapping.method()) {
                    implemented.add(httpMethod.name() + " "
                            + normalize(withoutApiPrefix(typeMapping.value()[0] + methodPath(method))));
                }
            }
        }
        assertThat(implemented).as("implemented vs documented Admin operations").isEqualTo(documented);
    }

    @Test
    void adminOperationsRequireAuthenticationAndTheDocumentedWriteHeaders() {
        Map<String, Object> securitySchemes = map(map(document.get("components")).get("securitySchemes"));
        assertThat(map(securitySchemes.get("sessionCookie")))
                .containsEntry("type", "apiKey")
                .containsEntry("in", "cookie")
                .containsEntry("name", "DB_SESSION");
        assertThat(map(securitySchemes.get("bearerAuth")))
                .containsEntry("type", "http")
                .containsEntry("scheme", "bearer");

        List<Map<String, Object>> globalSecurity = new ArrayList<>();
        for (Object entry : list(document.get("security"))) {
            globalSecurity.add(map(entry));
        }
        assertThat(globalSecurity).hasSize(2);
        assertThat(globalSecurity).anySatisfy(entry -> assertThat(entry).containsKey("sessionCookie"));
        assertThat(globalSecurity).anySatisfy(entry -> assertThat(entry).containsKey("bearerAuth"));

        for (Operation operation : OPERATIONS) {
            Map<String, Object> definition = map(map(paths.get(operation.path())).get(operation.method()));
            // Admin operations inherit the global authenticated schemes; an empty override would publish them
            // as public and is forbidden by design/04 §5.1.
            assertThat(definition).as("security override of " + operation.operationId())
                    .doesNotContainKey("security");

            List<String> parameters = parameterRefs(definition);
            assertThat(parameters).as("parameters of " + operation.operationId())
                    .contains("#/components/parameters/CorrelationId");

            if ("get".equals(operation.method())) {
                assertThat(parameters).doesNotContain(
                        "#/components/parameters/IdempotencyKey", "#/components/parameters/CsrfToken",
                        "#/components/parameters/StepUpChallenge");
                continue;
            }
            assertThat(parameters).as("write headers of " + operation.operationId())
                    .contains("#/components/parameters/IdempotencyKey",
                            "#/components/parameters/CsrfToken",
                            "#/components/parameters/StepUpChallenge");
            Map<String, Object> idempotency = parameter("IdempotencyKey");
            assertThat(idempotency).containsEntry("name", "Idempotency-Key")
                    .containsEntry("in", "header").containsEntry("required", true);
            assertThat(map(idempotency.get("schema"))).containsEntry("maxLength", 255);
            assertThat(parameter("CsrfToken")).containsEntry("name", "X-CSRF-Token");
            assertThat(parameter("StepUpChallenge")).containsEntry("name", "X-Step-Up-Challenge");

            // The controller must actually declare the documented write headers, not rely on undocumented input.
            Method handler = Arrays.stream(operation.controller().getDeclaredMethods())
                    .filter(method -> method.getName().equals(operation.handler()))
                    .findFirst()
                    .orElseThrow();
            Set<String> handlerHeaders = new LinkedHashSet<>();
            for (var parameter : handler.getParameters()) {
                RequestHeader header = parameter.getAnnotation(RequestHeader.class);
                if (header != null) {
                    handlerHeaders.add(header.name());
                }
            }
            assertThat(handlerHeaders).as("controller headers of " + operation.operationId())
                    .contains("Idempotency-Key", "X-Step-Up-Challenge");
            assertThat(handler.getParameters()).anySatisfy(parameter ->
                    assertThat(parameter.getAnnotation(RequestBody.class)).as("body of " + operation.operationId())
                            .isNotNull());
        }
    }

    @Test
    void documentedStatusSetsMatchTheApprovedContract() {
        for (Operation operation : OPERATIONS) {
            Map<String, Object> definition = map(map(paths.get(operation.path())).get(operation.method()));
            Map<String, Object> responses = map(definition.get("responses"));
            Set<Integer> statuses = new TreeSet<>();
            responses.keySet().forEach(key -> statuses.add(Integer.parseInt(key)));
            assertThat(statuses).as("documented statuses of " + operation.operationId())
                    .isEqualTo(DOCUMENTED_STATUSES.get(operation.operationId()));

            for (Map.Entry<String, Object> response : responses.entrySet()) {
                int status = Integer.parseInt(response.getKey());
                Map<String, Object> resolved = resolve(response.getValue());
                Map<String, Object> content = map(resolved.get("content"));
                String mediaType = status >= 400 ? "application/problem+json" : "application/json";
                assertThat(content).as("%s %s content", operation.operationId(), status)
                        .containsKey(mediaType);
                Map<String, Object> schema = map(map(content.get(mediaType)).get("schema"));
                Object reference = schema.get("$ref");
                String target = reference == null ? "" : reference.toString();
                if (status >= 400) {
                    assertThat(problemRequired(target))
                            .as("%s %d must use a Problem-derived schema", operation.operationId(), status)
                            .containsAll(problemFields());
                } else {
                    assertThat(target).as("%s %d success schema", operation.operationId(), status)
                            .startsWith("#/components/schemas/").isNotEqualTo("#/components/schemas/Problem");
                }
            }
        }

        // The statuses referenced by the document must be reachable by the shared error catalog, and no Admin
        // 422 exists outside the limit operations (Admin role/customer/approval paths never emit 422).
        Set<Integer> catalog = new LinkedHashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            catalog.add(code.httpStatus());
        }
        assertThat(catalog).contains(400, 401, 403, 404, 409, 422, 428, 429, 500, 503);
        for (Operation operation : OPERATIONS) {
            Set<Integer> statuses = DOCUMENTED_STATUSES.get(operation.operationId());
            boolean limitOperation = operation.path().startsWith("/admin/limits");
            assertThat(statuses.contains(422))
                    .as("422 is only part of the LIMIT_CHANGE contract; found on " + operation.operationId())
                    .isEqualTo(limitOperation);
        }
    }

    @Test
    void requestSchemasMatchTheControllerDtosAndDomainEnums() {
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));

        Map<String, Object> roleChange = map(schemas.get("RoleChangeRequest"));
        assertThat(list(roleChange.get("oneOf"))).hasSize(4);
        Map<String, Set<String>> variants = new LinkedHashMap<>();
        for (Object variant : list(roleChange.get("oneOf"))) {
            Map<String, Object> schema = map(resolve(variant));
            assertThat(schema).containsEntry("additionalProperties", false);
            Set<String> required = strings(schema.get("required"));
            Map<String, Object> properties = map(schema.get("properties"));
            Set<String> expectedProperties = new TreeSet<>(required);
            expectedProperties.add("reason");
            assertThat(strings(properties.keySet())).as("variant properties").isEqualTo(expectedProperties);
            Map<String, Object> operation = map(properties.get("operation"));
            assertThat(operation).containsEntry("const", operation.get("const"));
            variants.put(String.valueOf(operation.get("const")), required);
        }
        assertThat(variants.keySet()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(com.simulatedbank.banking.admin.domain.RoleChangeOperation.values())
                        .map(Enum::name).toList());
        assertThat(variants.get("ASSIGN_ROLE")).containsExactlyInAnyOrder(
                "operation", "targetUserId", "roleCode");
        assertThat(variants.get("REVOKE_ROLE")).containsExactlyInAnyOrder(
                "operation", "targetUserId", "roleCode");
        assertThat(variants.get("GRANT_PRIVILEGE")).containsExactlyInAnyOrder(
                "operation", "targetUserId", "privilegeCode");
        assertThat(variants.get("REVOKE_PRIVILEGE")).containsExactlyInAnyOrder(
                "operation", "targetUserId", "privilegeCode");

        // RoleChangeHttpRequest may only carry documented fields: an actor/status/tenant field would be an
        // undocumented client-authoritative input.
        Set<String> roleDto = new TreeSet<>();
        Arrays.stream(RoleChangeHttpRequest.class.getRecordComponents())
                .forEach(component -> roleDto.add(component.getName()));
        assertThat(roleDto).containsExactlyInAnyOrder("operation", "targetUserId", "roleCode", "privilegeCode",
                "reason");
        Set<String> documentedRoleFields = new TreeSet<>();
        variants.values().forEach(fields -> documentedRoleFields.addAll(fields));
        documentedRoleFields.add("reason");
        assertThat(roleDto).isSubsetOf(documentedRoleFields);

        Map<String, Object> decision = map(schemas.get("RoleApprovalRequest"));
        assertThat(decision).containsEntry("additionalProperties", false);
        assertThat(strings(decision.get("required")))
                .containsExactlyInAnyOrder("decision");
        Set<String> decisionDto = new TreeSet<>();
        Arrays.stream(ApprovalDecisionHttpRequest.class.getRecordComponents())
                .forEach(component -> decisionDto.add(component.getName()));
        assertThat(map(decision.get("properties")).keySet()).isEqualTo(decisionDto);
        assertThat(list(map(map(decision.get("properties")).get("decision")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ApprovalDecision.values()).map(Enum::name)
                        .toList());

        Map<String, Object> limit = map(schemas.get("LimitChangeRequest"));
        assertThat(limit).containsEntry("additionalProperties", false);
        Set<String> limitDto = new TreeSet<>();
        Arrays.stream(LimitChangeHttpRequest.class.getRecordComponents())
                .forEach(component -> limitDto.add(component.getName()));
        assertThat(strings(limit.get("required")))
                .containsExactlyInAnyOrder("customerId", "perTransactionLimit", "dailyLimit");
        assertThat(map(limit.get("properties")).keySet()).isEqualTo(limitDto);

        // Public approval projections must not advertise server-side/provider fields.
        Map<String, Object> summary = map(schemas.get("ApprovalSummary"));
        assertThat(strings(summary.get("required")))
                .containsExactlyInAnyOrder("approvalId", "requestType", "targetType", "targetId", "status",
                        "maker", "createdAt", "correlationId");
        assertThat(map(summary.get("properties"))).doesNotContainKeys("payload", "expectedVersion", "result");
        Map<String, Object> detail = map(schemas.get("ApprovalDetail"));
        assertThat(String.valueOf(map(list(detail.get("allOf")).getFirst()).get("$ref")))
                .isEqualTo("#/components/schemas/ApprovalSummary");
        assertThat(map(map(map(list(detail.get("allOf")).get(1)).get("properties")).get("payload")))
                .isNotNull();

        // Every published enum value must exist in the domain; unpublished domain values are the reserved
        // schema-only catalog (RETENTION_*, DATA_SCOPE, APPROVED) and must not be advertised as usable.
        assertDocumentedEnums(schemas, "ApprovalRequestType",
                Arrays.stream(ApprovalRequestType.values()).map(Enum::name).toList(),
                Arrays.stream(ApprovalRequestType.values()).filter(ApprovalRequestType::openInMvp)
                        .map(Enum::name).toList());
        assertDocumentedEnums(schemas, "ApprovalStatus",
                Arrays.stream(ApprovalStatus.values()).map(Enum::name).toList(),
                Arrays.stream(ApprovalStatus.values()).map(Enum::name).toList());
        assertDocumentedEnums(schemas, "ApprovalTargetType",
                Arrays.stream(ApprovalTargetType.values()).map(Enum::name).toList(),
                List.of("CUSTOMER", "ACCOUNT", "APP_USER"));
    }

    @Test
    void everyDocumentedReferenceResolvesToACompleteComponent() {
        for (Operation operation : OPERATIONS) {
            Map<String, Object> definition = map(map(paths.get(operation.path())).get(operation.method()));
            for (String parameter : parameterRefs(definition)) {
                Map<String, Object> resolved = resolve(Map.of("$ref", parameter));
                assertThat(resolved).as("parameter %s", parameter).containsKeys("name", "in", "schema");
                if (parameter.contains("Path")) {
                    assertThat(resolved).as("path parameter %s", parameter).containsEntry("required", true);
                }
            }
            Map<String, Object> responses = map(definition.get("responses"));
            for (Object response : responses.values()) {
                Map<String, Object> resolved = resolve(response);
                assertThat(resolved).containsKeys("description");
                assertThat(map(resolved.get("content"))).isNotEmpty();
            }
        }

        Map<String, Object> problem = map(map(map(document.get("components")).get("schemas")).get("Problem"));
        assertThat(strings(problem.get("required")))
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code",
                        "correlationId", "timestamp", "retryable");
    }

    /**
     * BE-ADM-REL-01: document-level validation that a hand-maintained YAML otherwise silently breaks — the
     * OpenAPI version, globally unique operation ids, and one-to-one agreement between each Admin path template,
     * its documented {@code in: path} parameter and the controller's {@code @PathVariable}.
     */
    @Test
    void documentEnvelopeAndPathParametersMatchEveryAdminOperation() {
        assertThat(String.valueOf(document.get("openapi"))).isEqualTo("3.1.0");
        assertThat(String.valueOf(map(document.get("info")).get("version"))).isNotBlank();

        Set<String> operationIds = new HashSet<>();
        for (Object item : paths.values()) {
            Map<String, Object> definitions = map(item);
            for (String method : List.of("get", "post", "put", "patch", "delete")) {
                if (!definitions.containsKey(method)) {
                    continue;
                }
                String operationId = String.valueOf(map(definitions.get(method)).get("operationId"));
                assertThat(operationIds.add(operationId))
                        .as("duplicate operationId in the canonical document: " + operationId).isTrue();
            }
        }

        Pattern template = Pattern.compile("\\{([^}]+)}");
        for (Operation operation : OPERATIONS) {
            Map<String, Object> definition = map(map(paths.get(operation.path())).get(operation.method()));
            Set<String> variables = new TreeSet<>();
            Matcher matcher = template.matcher(operation.path());
            while (matcher.find()) {
                variables.add(matcher.group(1));
            }

            Map<String, Boolean> documented = new HashMap<>();
            for (String reference : parameterRefs(definition)) {
                Map<String, Object> parameter = resolve(Map.of("$ref", reference));
                if ("path".equals(parameter.get("in"))) {
                    documented.put(String.valueOf(parameter.get("name")),
                            Boolean.TRUE.equals(parameter.get("required")));
                }
            }
            assertThat(documented.keySet()).as("path parameters of " + operation.operationId())
                    .isEqualTo(variables);
            documented.forEach((name, required) -> assertThat(required)
                    .as("path parameter %s of %s must be required", name, operation.operationId()).isTrue());

            Method handler = Arrays.stream(operation.controller().getDeclaredMethods())
                    .filter(method -> method.getName().equals(operation.handler()))
                    .findFirst()
                    .orElseThrow();
            RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(handler,
                    RequestMapping.class);
            String methodPath = methodMapping.value().length == 0 ? "" : methodMapping.value()[0];
            Set<String> controllerTemplate = new TreeSet<>();
            Matcher controllerMatcher = template.matcher(methodPath);
            while (controllerMatcher.find()) {
                controllerTemplate.add(controllerMatcher.group(1));
            }
            Set<String> controllerVariables = new TreeSet<>();
            for (java.lang.reflect.Parameter parameter : handler.getParameters()) {
                PathVariable binding = parameter.getAnnotation(PathVariable.class);
                if (binding != null) {
                    controllerVariables.add(binding.value().isEmpty() ? parameter.getName() : binding.value());
                }
            }
            // The controller must bind every variable of its own mapping; the document may use a different
            // variable name for the same positional path segment (07 keeps the public template as `{id}`).
            assertThat(controllerVariables).as("controller @PathVariable names of " + operation.operationId())
                    .isEqualTo(controllerTemplate);
            assertThat(controllerTemplate).as("path variable count of " + operation.operationId())
                    .hasSameSizeAs(variables);
        }
    }

    private static void assertDocumentedEnums(Map<String, Object> schemas, String name, List<String> domain,
            List<String> published) {
        Set<String> documented = strings(map(schemas.get(name)).get("enum"));
        assertThat(documented).as("documented %s values", name).isSubsetOf(domain);
        assertThat(documented).as("published %s values", name).isEqualTo(new TreeSet<>(published));
    }

    private static Set<String> problemRequired(String target) {
        Map<String, Object> schema = resolveSchema(target);
        Set<String> required = new TreeSet<>();
        collectRequired(schema, required);
        return required;
    }

    private static void collectRequired(Map<String, Object> schema, Set<String> required) {
        if (schema.containsKey("$ref")) {
            collectRequired(resolveSchema(schema.get("$ref").toString()), required);
            return;
        }
        if (schema.get("required") != null) {
            required.addAll(strings(schema.get("required")));
        }
        if (schema.get("allOf") != null) {
            list(schema.get("allOf")).forEach(part -> collectRequired(map(part), required));
        }
    }

    private static Map<String, Object> resolveSchema(String reference) {
        assertThat(reference).startsWith("#/components/schemas/");
        return resolve(Map.of("$ref", reference));
    }

    private static Set<String> problemFields() {
        return strings(resolveSchema("#/components/schemas/Problem").get("required"));
    }

    // ------------------------------------------------------------------ YAML helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).as("expected a YAML mapping").isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).as("expected a YAML list").isInstanceOf(List.class);
        return (List<Object>) value;
    }

    private static Set<String> strings(Object value) {
        Set<String> values = new TreeSet<>();
        if (value instanceof Map<?, ?> keys) {
            keys.keySet().forEach(entry -> values.add(String.valueOf(entry)));
            return values;
        }
        assertThat(value).as("expected a YAML list").isInstanceOf(Iterable.class);
        ((Iterable<?>) value).forEach(entry -> values.add(String.valueOf(entry)));
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(Object node) {
        Map<String, Object> value = map(node);
        Object reference = value.get("$ref");
        if (reference == null) {
            return value;
        }
        String pointer = reference.toString();
        assertThat(pointer).startsWith("#/");
        Map<String, Object> current = document;
        for (String token : pointer.substring(2).split("/")) {
            current = map(current.get(token));
        }
        return current;
    }

    private static List<String> parameterRefs(Map<String, Object> definition) {
        List<String> refs = new ArrayList<>();
        for (Object parameter : list(definition.get("parameters"))) {
            Object reference = map(parameter).get("$ref");
            assertThat(reference).as("Admin parameters must be shared components").isNotNull();
            refs.add(reference.toString());
        }
        return refs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameter(String name) {
        return map(map(map(document.get("components")).get("parameters")).get(name));
    }

    private static String methodPath(Method handler) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);
        assertThat(mapping).as("mapping of %s", handler.getName()).isNotNull();
        return mapping.value().length == 0 ? "" : mapping.value()[0];
    }

    /** Path variables are compared by position, not by name, because the document uses schema names. */
    private static String normalize(String path) {
        return withoutApiPrefix(path).replaceAll("\\{[^}]+}", "{}");
    }

    /** The OpenAPI paths are relative to the documented {@code /api/v1} server base. */
    private static String withoutApiPrefix(String path) {
        return path.startsWith("/api/v1/") ? path.substring("/api/v1".length()) : path;
    }
}
