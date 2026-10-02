package com.bank.simulator.shared.ratelimit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RateLimitPolicyFactory {

    private final RateLimitProperties properties;

    public RateLimitPolicyFactory(RateLimitProperties properties) {
        this.properties = properties;
    }

    public RateLimitPolicy policy(String operation) {
        return switch (operation) {
            case "login" -> new RateLimitPolicy(operation, properties.loginLimit(), properties.loginWindow());
            case "registration-otp" -> new RateLimitPolicy(operation, properties.registrationOtpLimit(), properties.registrationOtpWindow());
            case "recovery-initiate" -> new RateLimitPolicy(operation, properties.recoveryInitiateLimit(), properties.recoveryInitiateWindow());
            case "operator-otp" -> new RateLimitPolicy(operation, properties.operatorOtpLimit(), properties.operatorOtpWindow());
            case "recipient-resolve" -> new RateLimitPolicy(operation, properties.recipientResolveLimit(), properties.recipientResolveWindow());
            case "operator-lookup" -> new RateLimitPolicy(operation, properties.operatorLookupLimit(), properties.operatorLookupWindow());
            default -> throw new IllegalArgumentException("Unknown rate-limit operation");
        };
    }

    public String trustedIpKey(String operation, String clientIp) {
        return operation + ":ip:" + clientIp;
    }

    public String identifierKey(String operation, String identifier) {
        return operation + ":identifier:" + normalize(identifier);
    }

    public String actorKey(String operation, UUID actorId) {
        return operation + ":actor:" + actorId;
    }

    public UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof com.bank.simulator.identity.domain.AuthenticatedActor actor) {
            return actor.userId();
        }
        throw new IllegalStateException("Authenticated actor is required for this rate limit");
    }

    private String normalize(String identifier) {
        return identifier.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
