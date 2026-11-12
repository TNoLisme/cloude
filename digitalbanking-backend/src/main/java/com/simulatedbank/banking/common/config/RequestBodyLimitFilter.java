package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

final class RequestBodyLimitFilter extends OncePerRequestFilter {
    private final int limit;
    private final ProblemWriter writer;

    RequestBodyLimitFilter(int limit, ProblemWriter writer) {
        this.limit = limit;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/")) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            reject(request, response);
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) {
            reject(request, response);
            return;
        }
        var stream = new ServletInputStream() {
            private final ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            @Override public int read() { return input.read(); }
            @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Nonblocking request body reads are not supported");
            }
        };
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() { return stream; }
            @Override public BufferedReader getReader() {
                Charset charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8
                        : Charset.forName(getCharacterEncoding());
                return new BufferedReader(new InputStreamReader(stream, charset));
            }
        }, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writer.write(response, writer.create(ErrorCode.REQUEST_TOO_LARGE,
                ErrorCode.REQUEST_TOO_LARGE.detail(), request, List.of(), Map.of()));
    }
}
