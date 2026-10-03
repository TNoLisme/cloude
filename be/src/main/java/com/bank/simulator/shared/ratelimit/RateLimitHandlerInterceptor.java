package com.bank.simulator.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;

public class RateLimitHandlerInterceptor implements HandlerInterceptor {

    private final RateLimitInterceptor rateLimiter;
    private final RateLimitPolicyFactory policies;
    private final ClientIpResolver clientIpResolver;

    public RateLimitHandlerInterceptor(RateLimitInterceptor rateLimiter, RateLimitPolicyFactory policies,
                                       ClientIpResolver clientIpResolver) {
        this.rateLimiter = rateLimiter;
        this.policies = policies;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) {
        String path = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String operation = operation(request.getMethod(), path);
        if (operation == null) return true;
        RateLimitPolicy policy = policies.policy(operation);
        if (operation.equals("login") || operation.equals("registration-otp") || operation.equals("recovery-initiate")) {
            String identifier = bodyIdentifier(request, operation);
            rateLimiter.check(policies.trustedIpKey(operation, clientIpResolver.resolve(request)), policy);
            rateLimiter.check(policies.identifierKey(operation, identifier), policy);
            return true;
        }
        rateLimiter.check(policies.actorKey(operation, policies.currentActorId()), policy);
        return true;
    }

    private String bodyIdentifier(HttpServletRequest request, String operation) {
        Object body = request.getAttribute("cachedRequestBody");
        if (body instanceof String json) {
            String field = operation.equals("login") || operation.equals("registration-otp") ? "phone" : "identifier";
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path(field).asText("missing");
            } catch (Exception ignored) {
                return "missing";
            }
        }
        return "missing";
    }

    private String operation(String method, String path) {
        if ("POST".equals(method) && "/auth/login".equals(path)) return "login";
        if ("POST".equals(method) && "/auth/register/send-otp".equals(path)) return "registration-otp";
        if ("POST".equals(method) && "/auth/recover/initiate".equals(path)) return "recovery-initiate";
        if ("POST".equals(method) && "/operator/customers/send-otp".equals(path)) return "operator-otp";
        if ("POST".equals(method) && "/recipients/resolve".equals(path)) return "recipient-resolve";
        if ("GET".equals(method) && "/operator/customers".equals(path)) return "operator-lookup";
        if ("POST".equals(method) && path != null && path.matches("/operator/accounts/[^/]+/seed-balance")) return "operator-seed";
        if ("POST".equals(method) && path != null && path.matches("/operator/accounts/[^/]+/(?:block|unblock)")) return "operator-seed";
        return null;
    }
}
