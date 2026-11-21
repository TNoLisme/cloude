package com.simulatedbank.banking.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * BE-ADM-APP-04: the stdout encoder is the last fail-closed boundary before a security log leaves the process.
 * Admin audit facts may only carry the bounded approval catalog fields; client payload/notes/secrets never do.
 */
class SafeJsonLogEncoderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_AUDIT_LOGGER = "com.simulatedbank.banking.admin.AUDIT";
    private static final String WORKFLOW_LOGGER = "com.simulatedbank.banking.admin.WORKFLOW";
    private static final String WORKFLOW_EVENT = "ADMIN_APPROVAL_WORKFLOW";

    @Test
    void adminAuditFactIsSerializedWithAllowlistedFieldsOnly() throws Exception {
        ILoggingEvent event = capture(ADMIN_AUDIT_LOGGER, logger -> logger.atInfo()
                .addKeyValue("action", "ROLE_CHANGE_APPROVED")
                .addKeyValue("audit_class", "C2")
                .addKeyValue("outcome", "SUCCESS")
                .addKeyValue("actor_user_id", 1101L)
                .addKeyValue("entity_type", "APPROVAL_REQUEST")
                .addKeyValue("entity_id", "8001")
                .addKeyValue("request_type", "ROLE_CHANGE")
                .addKeyValue("reason_code", "ROLE_CONFLICT")
                .addKeyValue("correlation_id", "adm-log-corr")
                .addKeyValue("request_id", "1f0d6a4e-4ff1-4a7d-9a5c-2b8c6d0e1f22")
                // Free-text/unbounded keys a careless future change might add.
                .addKeyValue("payload", "{\"targetUserId\":\"1005\"}")
                .addKeyValue("decisionNote", "sensitive free text")
                .addKeyValue("otp", "812345")
                .addKeyValue("token", "Bearer synthetic-token")
                .log("SECURITY_AUDIT"));

        Map<String, Object> fields = encode(event);

        assertThat(fields).containsEntry("event", "SECURITY_AUDIT")
                .containsEntry("level", "INFO")
                .containsEntry("logger", ADMIN_AUDIT_LOGGER)
                .containsEntry("action", "ROLE_CHANGE_APPROVED")
                .containsEntry("audit_class", "C2")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("actor_user_id", 1101)
                .containsEntry("entity_type", "APPROVAL_REQUEST")
                .containsEntry("entity_id", "8001")
                .containsEntry("request_type", "ROLE_CHANGE")
                .containsEntry("reason_code", "ROLE_CONFLICT")
                .containsEntry("correlation_id", "adm-log-corr")
                .containsEntry("request_id", "1f0d6a4e-4ff1-4a7d-9a5c-2b8c6d0e1f22");
        assertThat(fields).doesNotContainKeys("payload", "decisionNote", "otp", "token", "message");
    }

    @Test
    void nonCatalogActionAndEntityAreDropped() throws Exception {
        ILoggingEvent event = capture(ADMIN_AUDIT_LOGGER, logger -> logger.atInfo()
                .addKeyValue("action", "TRANSFER_COMPLETED")
                .addKeyValue("entity_type", "ROLE")
                .addKeyValue("outcome", "REJECTED")
                .addKeyValue("reason_code", "NOT_AN_ERROR_CODE")
                .log("SECURITY_AUDIT"));

        Map<String, Object> fields = encode(event);

        assertThat(fields).containsEntry("event", "SECURITY_AUDIT")
                .containsEntry("outcome", "REJECTED");
        assertThat(fields).doesNotContainKeys("action", "entity_type", "reason_code");
    }

    @Test
    void writeFailureFactKeepsOnlyBoundedFailureFields() throws Exception {
        ILoggingEvent event = capture(ADMIN_AUDIT_LOGGER, logger -> logger.atError()
                .addKeyValue("action", "SECURITY_ACCESS_DENIED")
                .addKeyValue("audit_class", "C3")
                .addKeyValue("outcome", "WRITE_FAILED")
                .addKeyValue("correlation_id", "adm-log-corr")
                .addKeyValue("stackTrace", "java.lang.IllegalStateException: audit_log unavailable")
                .log("SECURITY_AUDIT_WRITE_FAILED"));

        Map<String, Object> fields = encode(event);

        assertThat(fields).containsEntry("event", "SECURITY_AUDIT_WRITE_FAILED")
                .containsEntry("action", "SECURITY_ACCESS_DENIED")
                .containsEntry("audit_class", "C3")
                .containsEntry("outcome", "WRITE_FAILED");
        assertThat(fields).doesNotContainKeys("stackTrace", "message");
    }

    @Test
    void workflowEventKeepsOnlyBoundedKeys() throws Exception {
        ILoggingEvent event = capture(WORKFLOW_LOGGER, logger -> logger.atInfo()
                .addKeyValue("stage", "DECISION")
                .addKeyValue("request_type", "ROLE_CHANGE")
                .addKeyValue("decision", "APPROVE")
                .addKeyValue("provider", "IDENTITY")
                .addKeyValue("operation", "APPLY_ROLE_CHANGE")
                .addKeyValue("outcome", "SUCCESS")
                .addKeyValue("error_code", "ROLE_CONFLICT")
                .addKeyValue("attempts", "3")
                .addKeyValue("age_bucket", "30M_1H")
                .addKeyValue("approval_id", "8001")
                .addKeyValue("correlation_id", "adm-ops-correlation")
                .addKeyValue("request_id", "1f0d6a4e-4ff1-4a7d-9a5c-2b8c6d0e1f22")
                // Unbounded/secret-looking keys a careless change might add.
                .addKeyValue("payload", "{\"targetUserId\":\"1005\"}")
                .addKeyValue("note", "sensitive free text")
                .addKeyValue("token", "Bearer synthetic-token")
                .log(WORKFLOW_EVENT));

        Map<String, Object> fields = encode(event);

        assertThat(fields).containsEntry("event", WORKFLOW_EVENT)
                .containsEntry("stage", "DECISION")
                .containsEntry("request_type", "ROLE_CHANGE")
                .containsEntry("decision", "APPROVE")
                .containsEntry("provider", "IDENTITY")
                .containsEntry("operation", "APPLY_ROLE_CHANGE")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("error_code", "ROLE_CONFLICT")
                .containsEntry("attempts", "3")
                .containsEntry("age_bucket", "30M_1H")
                .containsEntry("approval_id", "8001")
                .containsEntry("correlation_id", "adm-ops-correlation")
                .containsEntry("request_id", "1f0d6a4e-4ff1-4a7d-9a5c-2b8c6d0e1f22");
        assertThat(fields).doesNotContainKeys(
                "payload", "note", "token", "message", "throwable", "stackTrace");
    }

    @Test
    void workflowUnboundedOrSecretLookingValuesAreDropped() throws Exception {
        ILoggingEvent event = capture(WORKFLOW_LOGGER, logger -> logger.atInfo()
                .addKeyValue("stage", "proposal")
                .addKeyValue("request_type", "RETENTION_HOLD")
                .addKeyValue("decision", "MAYBE")
                .addKeyValue("provider", "EXTERNAL")
                .addKeyValue("operation", "drop table")
                .addKeyValue("outcome", "UNKNOWN")
                .addKeyValue("error_code", "NOT_AN_ERROR_CODE")
                .addKeyValue("attempts", "99999999")
                .addKeyValue("age_bucket", "YESTERDAY")
                .addKeyValue("approval_id", "abc")
                .addKeyValue("correlation_id", "not a correlation id")
                .addKeyValue("request_id", "not-a-uuid")
                .log(WORKFLOW_EVENT));

        Map<String, Object> fields = encode(event);

        assertThat(fields).containsEntry("event", WORKFLOW_EVENT);
        assertThat(fields.get("correlation_id")).isNull();
        assertThat(fields.get("request_id")).isNull();
        assertThat(fields).doesNotContainKeys(
                "stage", "request_type", "decision", "provider", "operation",
                "outcome", "error_code", "attempts", "age_bucket", "approval_id");
    }

    @Test
    void workflowLoggerWithAnotherMessageStaysGeneric() throws Exception {
        ILoggingEvent event = capture(WORKFLOW_LOGGER, logger -> logger.atInfo()
                .addKeyValue("approval_id", "8001")
                .log("some diagnostic"));

        Map<String, Object> fields = encode(event);

        assertThat(fields).containsEntry("event", "APPLICATION_LOG");
        assertThat(fields).doesNotContainKey("approval_id");
    }

    @Test
    void nonAuditLoggerNeverEmitsKeyValuesOrRawMessage() throws Exception {
        ILoggingEvent event = capture("com.simulatedbank.banking.other", logger -> logger.atInfo()
                .addKeyValue("password", "synthetic-secret")
                .addKeyValue("customerName", "Nguyễn Văn An")
                .log("some diagnostic"));

        Map<String, Object> fields = encode(event);

        assertThat(fields).containsEntry("event", "APPLICATION_LOG");
        assertThat(fields).doesNotContainKeys("password", "customerName", "message");
    }

    private static ILoggingEvent capture(String loggerName, Consumer<Logger> action) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.accept(logger);
            return appender.list.getFirst();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Map<String, Object> encode(ILoggingEvent event) throws Exception {
        byte[] bytes = new SafeJsonLogEncoder().encode(event);
        return JSON.readValue(new String(bytes, StandardCharsets.UTF_8).trim(),
                new TypeReference<Map<String, Object>>() { });
    }
}
