package com.bank.simulator.identity.web;

import com.bank.simulator.shared.ratelimit.CachedBodyFilter;
import com.bank.simulator.shared.ratelimit.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerAuthenticationFilter bearerFilter,
                                            CsrfHeaderFilter csrfHeaderFilter, CachedBodyFilter cachedBodyFilter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/health",
                                "/__local/otp-mailbox",
                                "/auth/register/send-otp",
                                "/auth/register",
                                "/auth/login",
                                "/auth/csrf",
                                "/auth/refresh",
                                "/auth/recover/initiate",
                                "/auth/recover/verify",
                                "/auth/recover/confirm",
                                "/v3/api-docs/**",
                                "/api/v1/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/api/v1/swagger-ui/**",
                                "/swagger-ui.html",
                                "/webjars/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(cachedBodyFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(csrfHeaderFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .build();
    }
}
