package com.simulatedbank.banking.common.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.ProblemWriter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class RequestContextTest {
    final ProblemWriter writer = new ProblemWriter(Clock.systemUTC(), new ObjectMapper().findAndRegisterModules());
    final CorrelationAndRequestIdFilter filter = new CorrelationAndRequestIdFilter(writer);

    @AfterEach
    void clean() {
        assertClean();
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "flow:retry_1.0-2", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void missingOrValidHeaderAndRetriesHaveConsistentIds(String header) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var request = new MockHttpServletRequest();
            if (!header.isEmpty()) request.addHeader("X-Correlation-ID", header);
            request.addHeader("X-Request-ID", "client-controlled");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                var context = RequestContextHolder.current().orElseThrow();
                assertThat(context.correlationId()).isEqualTo(response.getHeader("X-Correlation-ID"));
                assertThat(MDC.get("correlationId")).isEqualTo(context.correlationId());
                assertThat(MDC.get("requestId")).isEqualTo(context.requestId());
                assertThat(UUID.fromString(context.requestId()).version()).isEqualTo(4);
                ids.add(context.requestId());
                if (header.isEmpty()) assertThat(UUID.fromString(context.correlationId()).version()).isEqualTo(4);
                else assertThat(context.correlationId()).isEqualTo(header);
            });
            assertClean();
        }
        assertThat(ids).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad\r\nINJECTED", "bad/email@example.test", "é", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void invalidHeaderNeverReachesDownstreamOrOutput(String raw, CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", raw);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Downstream called"); });
        assertThat(response.getStatus()).isEqualTo(400);
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("correlationId").asText()).isEqualTo(response.getHeader("X-Correlation-ID"));
        assertThat(UUID.fromString(body.path("correlationId").asText()).version()).isEqualTo(4);
        if (!raw.isBlank()) assertThat(response.getContentAsString() + output.getAll()).doesNotContain(raw);
        assertThat(output.getAll()).doesNotContain("INJECTED", "example.test");
    }

    @Test
    void duplicateHeadersAreRejected() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", new String[]{"one", "two"});
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void downstreamProblemWriterRetainsColonCorrelation(int status) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "flow:retry");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            ErrorCode code = status == 401 ? ErrorCode.AUTH_SESSION_EXPIRED
                    : status == 403 ? ErrorCode.AUTH_FORBIDDEN : ErrorCode.NOT_FOUND;
            writer.write(response, writer.create(code, code.detail(), request, List.of(), Map.of()));
        });
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("flow:retry");
        assertThat(response.getContentAsString()).contains("\"correlationId\":\"flow:retry\"");
    }

    @Test
    void exceptionsBeforeMvcAreSafeAndCommittedResponsesAreNotRewritten(CapturedOutput output) throws Exception {
        for (Exception failure : List.of(new ServletException("PRIVATE_EMAIL@example.test"),
                new IOException("PRIVATE_EMAIL@example.test"), new IllegalStateException("PRIVATE_EMAIL@example.test"))) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
                if (failure instanceof ServletException e) throw e;
                if (failure instanceof IOException e) throw e;
                throw (RuntimeException) failure;
            });
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("PRIVATE_EMAIL");
            assertClean();
        }
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest();
        filter.doFilter(request, response, (req, res) -> {
            response.getWriter().write("already sent");
            response.flushBuffer();
            throw new ServletException("PRIVATE_EMAIL@example.test");
        });
        assertThat(response.getContentAsString()).isEqualTo("already sent");
        request.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Double write"); });
        assertThat(response.getContentAsString()).isEqualTo("already sent");
        assertThat(output.getAll()).doesNotContain("PRIVATE_EMAIL");
    }

    @Test
    void asyncErrorAndNestedErrorRedispatchReuseSnapshotAndCleanThreads() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        List<RequestContext> contexts = new ArrayList<>();
        filter.doFilter(request, response, (req, res) -> {
            contexts.add(RequestContextHolder.current().orElseThrow());
            request.setDispatcherType(DispatcherType.ERROR);
            filter.doFilter(request, response, (r, s) -> contexts.add(RequestContextHolder.current().orElseThrow()));
            assertThat(RequestContextHolder.current()).contains(contexts.getFirst());
        });
        assertClean();
        for (DispatcherType type : List.of(DispatcherType.ASYNC, DispatcherType.ERROR)) {
            request.setDispatcherType(type);
            filter.doFilter(request, response, (req, res) -> contexts.add(RequestContextHolder.current().orElseThrow()));
            assertClean();
        }
        assertThat(contexts).hasSize(4).allMatch(contexts.getFirst()::equals);
        // A handled validation response is never rendered twice on redispatch.
        var invalid = new MockHttpServletRequest();
        invalid.addHeader("X-Correlation-ID", "");
        var error = new MockHttpServletResponse();
        filter.doFilter(invalid, error, (req, res) -> { throw new AssertionError(); });
        String original = error.getContentAsString();
        invalid.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(invalid, error, (req, res) -> { throw new AssertionError(); });
        assertThat(error.getContentAsString()).isEqualTo(original);
    }

    @Test
    void concurrentRequestsAndReusedWorkersNeverShareContext() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var barrier = new CyclicBarrier(4);
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String id = "flow-" + i;
                tasks.add(() -> {
                    assertClean();
                    var request = new MockHttpServletRequest();
                    request.addHeader("X-Correlation-ID", id);
                    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                        try { barrier.await(); } catch (Exception e) { throw new ServletException(e); }
                        assertThat(RequestContextHolder.current().orElseThrow().correlationId()).isEqualTo(id);
                        assertThat(MDC.get("correlationId")).isEqualTo(id);
                    });
                    assertClean();
                    return id;
                });
            }
            for (var result : workers.invokeAll(tasks)) assertThat(result.get()).startsWith("flow-");
        }
    }

    @Test
    void explicitHandoffRestoresWorkerMdcEvenOnFailure() throws Exception {
        var snapshot = new RequestContext("flow:original", UUID.randomUUID().toString(), null, null);
        try (var workers = Executors.newSingleThreadExecutor(); var scope = RequestContextHolder.bind(snapshot)) {
            RequestContext captured = RequestContextHolder.current().orElseThrow();
            workers.submit(() -> {
                assertClean(); // no inheritable thread local
                MDC.put("other", "worker-owned");
                assertThatThrownBy(() -> {
                    try (var binding = RequestContextHolder.bind(captured)) {
                        assertThat(MDC.get("correlationId")).isEqualTo("flow:original");
                        throw new IllegalStateException("synthetic");
                    }
                }).isInstanceOf(IllegalStateException.class);
                assertClean();
                assertThat(MDC.get("other")).isEqualTo("worker-owned");
                MDC.clear();
            }).get();
            workers.submit(RequestContextTest::assertClean).get();
            assertThat(RequestContextHolder.current()).contains(snapshot);
        }
    }

    @Test
    void metadataIgnoresUntrustedProxyAndNeverLogsRawIpOrAgent(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.19");
        request.addHeader("Forwarded", "for=198.51.100.123");
        request.addHeader("X-Forwarded-For", "198.51.100.123");
        request.addHeader("User-Agent", "Agent\r\n\t\u001b\u2028\u202e" + "x".repeat(300));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var context = RequestContextHolder.current().orElseThrow();
            assertThat(context.clientIp()).isEqualTo("203.0.113.***");
            assertThat(context.userAgent()).hasSize(256).doesNotContain("\r", "\n", "\t", "\u001b", "\u2028", "\u202e");
            assertThat(context.toString()).doesNotContain("203.0.113", "Agent");
        });
        assertThat(new RequestContext("flow", UUID.randomUUID().toString(), "2001:db8::1", null).clientIp()).isNull();
        assertThat(output.getAll()).doesNotContain("203.0.113.19", "198.51.100.123", "Agent");
    }

    static void assertClean() {
        assertThat(RequestContextHolder.current()).isEmpty();
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("requestId")).isNull();
    }
}
