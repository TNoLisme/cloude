package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.simulatedbank.banking.common.context.CorrelationAndRequestIdFilter;
import com.simulatedbank.banking.common.context.RequestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;

/** BE-CUS-IMP-07: audit C3 của security chain giữ actor/target/trace mà không nhận metadata client. */
class SecurityAccessDeniedAuditorTest {

    private static final Logger AUDIT_LOGGER =
            (Logger) LoggerFactory.getLogger("com.simulatedbank.banking.common.AUDIT");

    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        logEvents.start();
        AUDIT_LOGGER.addAppender(logEvents);
    }

    @AfterEach
    void tearDown() {
        AUDIT_LOGGER.detachAppender(logEvents);
        logEvents.stop();
        registry.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedDenialCarriesTrustedPrincipalTargetAndRequestTrace() {
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/customers/2001");
        request.setAttribute(CorrelationAndRequestIdFilter.CONTEXT_ATTRIBUTE,
                new RequestContext("cus-denied-correlation", "76b80b5c-2ae8-4c8c-b164-959feb6f00e8",
                        "203.0.113.10", "synthetic-agent"));
        var session = new SessionAuthenticator.Session("501", null,
                Set.of("ROLE_CUSTOMER", "ACCOUNT_READ_SELF"), "a".repeat(64));
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                session, null, java.util.List.of());
        AuthorizationDeniedEvent<MockHttpServletRequest> event = mock(AuthorizationDeniedEvent.class);
        Supplier<Authentication> authenticationSupplier = () -> authentication;
        when(event.getAuthentication()).thenReturn(authenticationSupplier);
        when(event.getObject()).thenReturn(request);

        new SecurityAccessDeniedAuditor(registry, ignored -> {}).onAuthorizationDenied(event);

        assertThat(logEvents.list).singleElement().satisfies(log -> {
            assertThat(log.getTimeStamp()).isPositive();
            assertThat(log.getFormattedMessage()).isEqualTo("SECURITY_AUDIT");
            Map<String, Object> fields = fields(log);
            assertThat(fields).containsEntry("action", "SECURITY_ACCESS_DENIED")
                    .containsEntry("outcome", "REJECTED")
                    .containsEntry("actor_user_id", 501L)
                    .containsEntry("actor_roles", "CUSTOMER")
                    .containsEntry("entity_type", "CUSTOMER")
                    .containsEntry("entity_id", "2001")
                    .containsEntry("reason_code", "AUTH_FORBIDDEN")
                    .containsEntry("correlation_id", "cus-denied-correlation")
                    .containsEntry("request_id", "76b80b5c-2ae8-4c8c-b164-959feb6f00e8");
            assertThat(fields.toString()).doesNotContain(
                    "synthetic-agent", "203.0.113.10", "Bearer", "token");
        });
        assertThat(registry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", "CUSTOMER").count()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminRoleProposalDenialAuditsTheApprovalRequestEntity() {
        new SecurityAccessDeniedAuditor(registry, ignored -> {}).onAuthorizationDenied(
                denial("POST", "/api/v1/admin/roles", new SessionAuthenticator.Session("501", null,
                        Set.of("ROLE_OPERATIONS", "LIMIT_MAKER"), "a".repeat(64))));

        assertThat(logEvents.list).singleElement().satisfies(log -> {
            Map<String, Object> fields = fields(log);
            assertThat(fields).containsEntry("action", "SECURITY_ACCESS_DENIED")
                    .containsEntry("outcome", "REJECTED")
                    .containsEntry("actor_user_id", 501L)
                    .containsEntry("actor_roles", "OPERATIONS")
                    // design/01 §6.3: limit/role change dùng APPROVAL_REQUEST; `ROLE` không thuộc catalog.
                    .containsEntry("entity_type", "APPROVAL_REQUEST")
                    .containsEntry("reason_code", "AUTH_FORBIDDEN");
            assertThat(fields.toString()).doesNotContain("ROLE_OPERATIONS", "LIMIT_MAKER", "a".repeat(64));
        });
        assertThat(registry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", "APPROVAL_REQUEST").count()).isEqualTo(1.0);
        assertThat(registry.find("security.audit.events").tag("entity_type", "ROLE").counter()).isNull();
        assertThat(registry.find("security.audit.events").tag("entity_type", "UNKNOWN").counter()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminApproveDenialKeepsTheApprovalIdentifier() {
        new SecurityAccessDeniedAuditor(registry, ignored -> {}).onAuthorizationDenied(
                denial("POST", "/api/v1/admin/limits/7001/approve", new SessionAuthenticator.Session("502", null,
                        Set.of("ROLE_OPERATIONS", "LIMIT_MAKER"), "b".repeat(64))));

        assertThat(logEvents.list).singleElement().satisfies(log -> {
            Map<String, Object> fields = fields(log);
            assertThat(fields).containsEntry("entity_type", "APPROVAL_REQUEST")
                    .containsEntry("entity_id", "7001");
            assertThat(fields.toString()).doesNotContain("b".repeat(64));
        });
        assertThat(registry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", "APPROVAL_REQUEST").count()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void customerSelfLimitAndNonAdminResourcesKeepTheirCatalogEntity() {
        var auditor = new SecurityAccessDeniedAuditor(registry, ignored -> {});

        auditor.onAuthorizationDenied(denial("GET", "/api/v1/limits", new SessionAuthenticator.Session("503",
                null, Set.of("ROLE_CUSTOMER"), "c".repeat(64))));
        auditor.onAuthorizationDenied(denial("GET", "/api/v1/admin/customers/2001/accounts",
                new SessionAuthenticator.Session("504", null, Set.of("ROLE_AUDITOR"), "d".repeat(64))));

        assertThat(logEvents.list).hasSize(2);
        assertThat(fields(logEvents.list.get(0))).containsEntry("entity_type", "LIMIT_CONFIG");
        assertThat(fields(logEvents.list.get(1))).containsEntry("entity_type", "ACCOUNT");
        assertThat(registry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", "LIMIT_CONFIG").count()).isEqualTo(1.0);
        assertThat(registry.counter("security.audit.events", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "REJECTED", "entity_type", "ACCOUNT").count()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anonymousDenialIsNotAuditedAsAnAccessDenial() {
        AuthorizationDeniedEvent<MockHttpServletRequest> event = mock(AuthorizationDeniedEvent.class);
        when(event.getAuthentication()).thenReturn(() -> null);
        when(event.getObject()).thenReturn(new MockHttpServletRequest("POST", "/api/v1/admin/roles"));

        new SecurityAccessDeniedAuditor(registry, ignored -> {}).onAuthorizationDenied(event);

        // design/01 §5.7: 401 authentication failures are not 403 authorization denials.
        assertThat(logEvents.list).isEmpty();
        assertThat(registry.find("security.audit.events").counters()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeFailureIsCountedWithoutDroppingTheDenial() {
        // A broken event source makes record(...) throw; the chain response must stay a 403.
        AuthorizationDeniedEvent<MockHttpServletRequest> event = mock(AuthorizationDeniedEvent.class);
        when(event.getAuthentication()).thenReturn(() -> {
            throw new IllegalStateException("snapshot unavailable");
        });

        assertThatCode(() -> new SecurityAccessDeniedAuditor(registry, ignored -> {}).onAuthorizationDenied(event))
                .doesNotThrowAnyException();

        assertThat(registry.counter("security.audit.failures", "action", "SECURITY_ACCESS_DENIED",
                "outcome", "WRITE_FAILED").count()).isEqualTo(1.0);
        assertThat(registry.find("security.audit.events").counters()).isEmpty();
        assertThat(logEvents.list).singleElement().satisfies(log ->
                assertThat(log.getFormattedMessage()).isEqualTo("SECURITY_AUDIT_WRITE_FAILED"));
    }

    private static AuthorizationDeniedEvent<MockHttpServletRequest> denial(String method, String uri,
            SessionAuthenticator.Session session) {
        var request = new MockHttpServletRequest(method, uri);
        request.setAttribute(CorrelationAndRequestIdFilter.CONTEXT_ATTRIBUTE,
                new RequestContext("adm-denied-correlation", "1f0d6a4e-4ff1-4a7d-9a5c-2b8c6d0e1f22",
                        "203.0.113.10", "synthetic-agent"));
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                session, null, java.util.List.of());
        AuthorizationDeniedEvent<MockHttpServletRequest> event = mock(AuthorizationDeniedEvent.class);
        when(event.getAuthentication()).thenReturn(() -> authentication);
        when(event.getObject()).thenReturn(request);
        return event;
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> pair.value != null)
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
