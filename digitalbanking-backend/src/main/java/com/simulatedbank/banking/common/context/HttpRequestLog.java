package com.simulatedbank.banking.common.context;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerMapping;

/** One completion record across REQUEST/ASYNC/ERROR dispatches; no raw URI, query, headers or body. */
public final class HttpRequestLog implements AsyncListener {
    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestLog.class);
    private static final String ATTRIBUTE = HttpRequestLog.class.getName();
    public static final String USER_ATTRIBUTE = ATTRIBUTE + ".user";
    public static final String ERROR_ATTRIBUTE = ATTRIBUTE + ".error";
    private final long started = System.nanoTime();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final RequestContext context;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private boolean listening;

    private HttpRequestLog(HttpServletRequest request, HttpServletResponse response, RequestContext context) {
        this.request = request;
        this.response = response;
        this.context = context;
    }

    public static HttpRequestLog start(HttpServletRequest request, HttpServletResponse response, RequestContext context) {
        var existing = (HttpRequestLog) request.getAttribute(ATTRIBUTE);
        if (existing != null) return existing;
        var log = new HttpRequestLog(request, response, context);
        request.setAttribute(ATTRIBUTE, log);
        return log;
    }

    public static String maskedUserId(String id) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required");
        }
    }

    public void finishDispatch() {
        if (request.isAsyncStarted()) {
            if (!listening) {
                request.getAsyncContext().addListener(this);
                listening = true;
            }
        } else {
            complete();
        }
    }

    private void complete() {
        if (!completed.compareAndSet(false, true)) return;
        try (var scope = RequestContextHolder.bind(context)) {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern instanceof String template ? template : "UNMATCHED";
            String method = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                    .contains(request.getMethod()) ? request.getMethod() : "OTHER";
            int status = response.getStatus();
            LOG.atInfo().addKeyValue("route", route).addKeyValue("method", method)
                    .addKeyValue("status", status).addKeyValue("latency_ms", (System.nanoTime() - started) / 1_000_000)
                    .addKeyValue("user_id", request.getAttribute(USER_ATTRIBUTE))
                    .addKeyValue("error_code", request.getAttribute(ERROR_ATTRIBUTE))
                    .addKeyValue("action", "HTTP_REQUEST")
                    .addKeyValue("outcome", status >= 500 ? "SERVER_ERROR" : status >= 400 ? "CLIENT_ERROR" : "SUCCESS")
                    .log("HTTP_REQUEST_COMPLETED");
        }
    }

    @Override public void onComplete(AsyncEvent event) { complete(); }
    @Override public void onTimeout(AsyncEvent event) { /* Completion records the final response status. */ }
    @Override public void onError(AsyncEvent event) { /* Never serialize the throwable. */ }
    @Override public void onStartAsync(AsyncEvent event) throws IOException { event.getAsyncContext().addListener(this); }
}
