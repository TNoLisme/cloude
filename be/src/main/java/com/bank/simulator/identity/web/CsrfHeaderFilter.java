package com.bank.simulator.identity.web;

import com.bank.simulator.identity.infrastructure.security.CsrfTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class CsrfHeaderFilter extends OncePerRequestFilter {

    private final CsrfTokenService csrfTokenService;

    public CsrfHeaderFilter(CsrfTokenService csrfTokenService) {
        this.csrfTokenService = csrfTokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(HttpMethod.POST.matches(request.getMethod())
                && (path.equals("/auth/refresh") || path.equals("/auth/logout")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader("X-CSRF-Token");
        if (!csrfTokenService.isValid(token)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
