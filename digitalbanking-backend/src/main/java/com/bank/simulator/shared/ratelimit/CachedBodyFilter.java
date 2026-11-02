package com.bank.simulator.shared.ratelimit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Component
public class CachedBodyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                                    jakarta.servlet.FilterChain chain) throws jakarta.servlet.ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        CachedRequest wrapped = new CachedRequest(request, body);
        wrapped.setAttribute("cachedRequestBody", new String(body, java.nio.charset.StandardCharsets.UTF_8));
        chain.doFilter(wrapped, response);
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }
        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
            };
        }
    }
}
