package com.simulatedbank.banking.common.context;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.MDC;

/** Not inheritable: capture current() explicitly before scheduling work. */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {}

    public static Optional<RequestContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove("correlationId");
        MDC.remove("requestId");
    }

    /** Bind on the execution thread using try-with-resources, including async/job failures. */
    public static Scope bind(RequestContext context) {
        Objects.requireNonNull(context);
        RequestContext previous = CURRENT.get();
        String correlationId = MDC.get("correlationId");
        String requestId = MDC.get("requestId");
        CURRENT.set(context);
        MDC.put("correlationId", context.correlationId());
        MDC.put("requestId", context.requestId());
        return () -> {
            clear();
            if (previous != null) {
                CURRENT.set(previous);
            }
            if (correlationId != null) {
                MDC.put("correlationId", correlationId);
            }
            if (requestId != null) {
                MDC.put("requestId", requestId);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
