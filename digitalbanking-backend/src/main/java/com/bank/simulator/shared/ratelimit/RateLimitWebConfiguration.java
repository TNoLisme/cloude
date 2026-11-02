package com.bank.simulator.shared.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

@Configuration
public class RateLimitWebConfiguration implements WebMvcConfigurer {

    private final RateLimitHandlerInterceptor rateLimitHandlerInterceptor;

    public RateLimitWebConfiguration(RateLimitInterceptor rateLimiter, RateLimitPolicyFactory policies,
                                    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.trusted-proxies:}") String trustedProxies) {
        Set<String> addresses = trustedProxies.isBlank() ? Set.of() : Set.of(trustedProxies.split(","));
        this.rateLimitHandlerInterceptor = new RateLimitHandlerInterceptor(rateLimiter, policies,
                new ClientIpResolver(addresses));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitHandlerInterceptor);
    }
}
