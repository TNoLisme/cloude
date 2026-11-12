package com.simulatedbank.banking.common.context;

import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/** Registered only as a servlet filter, outside and before Spring Security. */
public class CorrelationAndRequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String CONTEXT_ATTRIBUTE = RequestContext.class.getName();
    private static final String HANDLED_ATTRIBUTE = CorrelationAndRequestIdFilter.class.getName() + ".handled";
    private final ProblemWriter writer;

    public CorrelationAndRequestIdFilter(ProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        RequestContext context = (RequestContext) request.getAttribute(CONTEXT_ATTRIBUTE);
        boolean invalid = false;
        if (context == null) {
            List<String> values = Collections.list(request.getHeaders(HEADER));
            String supplied = values.isEmpty() ? null : values.getFirst();
            invalid = values.size() > 1 || (supplied != null && !RequestContext.isValidCorrelationId(supplied));
            String correlationId = supplied == null || invalid ? UUID.randomUUID().toString() : supplied;
            context = new RequestContext(correlationId, UUID.randomUUID().toString(),
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            request.setAttribute(CONTEXT_ATTRIBUTE, context);
        }
        request.setAttribute("correlationId", context.correlationId());
        response.setHeader(HEADER, context.correlationId());
        try (var scope = RequestContextHolder.bind(context)) {
            if (Boolean.TRUE.equals(request.getAttribute(HANDLED_ATTRIBUTE))) {
                return;
            }
            if (invalid) {
                fail(request, response, ErrorCode.VALIDATION_ERROR);
                return;
            }
            try {
                chain.doFilter(request, response);
            } catch (ServletException | IOException | RuntimeException exception) {
                // No raw exception, metadata or invalid header enters logs/Problem.
                fail(request, response, ErrorCode.INTERNAL_ERROR);
            }
        }
    }

    private void fail(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        request.setAttribute(HANDLED_ATTRIBUTE, true);
        writer.write(response, writer.create(code, code.detail(), request, List.of(), Map.of()));
    }
}
