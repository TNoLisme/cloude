package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.common.error.ProblemWriter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityFilterChainConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AppProperties properties,
            ObjectProvider<SessionAuthenticator> authenticators, RequestRateLimiter limiter, ProblemWriter writer)
            throws Exception {
        var csrfTokens = new SessionCsrfTokens();
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(csrfTokens)
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .requireCsrfProtectionMatcher(request ->
                                Boolean.TRUE.equals(request.getAttribute(SessionAuthenticationFilter.COOKIE_AUTH))
                                        && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> problem(writer, request, response,
                                ErrorCode.AUTH_SESSION_EXPIRED))
                        .accessDeniedHandler((request, response, exception) -> problem(writer, request, response,
                                ErrorCode.AUTH_FORBIDDEN)))
                .headers(headers -> headers
                        .cacheControl(Customizer.withDefaults())
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasAnyRole("ADMIN", "AUDITOR")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/passcode",
                                "/api/v1/auth/fido/verify", "/api/v1/auth/otp/verify",
                                "/api/v1/auth/challenges/*/resend").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/customers/me", "/api/v1/accounts")
                                .hasAuthority("ACCOUNT_READ_SELF")
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts/*").hasAnyAuthority("ACCOUNT_READ_SELF", "ACCOUNT_READ")
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers").hasAuthority("TRANSFER_CREATE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/transfers/*", "/api/v1/transactions", "/api/v1/transactions/*")
                                .hasAnyAuthority("TRANSACTION_READ_SELF", "TRANSACTION_READ")
                        .requestMatchers(HttpMethod.GET, "/api/v1/limits").hasAuthority("LIMIT_READ_SELF")
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/export").hasAuthority("AUDIT_EXPORT")
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit", "/api/v1/audit/*").hasAuthority("AUDIT_READ")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/customers/*/accounts").hasAuthority("ACCOUNT_READ")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/customers/*").hasAuthority("CUSTOMER_READ")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/limits/*/approve")
                                .access(AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasAuthority("CHECKER"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("LIMIT_MAKER", "LIMIT_MANAGE")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/limits").hasAnyAuthority("LIMIT_MAKER", "LIMIT_MANAGE")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/roles/*/approve")
                                .access(AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasAuthority("CHECKER"),
                                        AuthorityAuthorizationManager.hasAuthority("ROLE_MANAGE")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/roles").hasAuthority("ROLE_MANAGE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/approvals", "/api/v1/admin/approvals/*")
                                .hasAnyRole("OPERATIONS", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/fraud-flags").hasAnyAuthority("FRAUD_REVIEW", "FRAUD_READ")
                        .requestMatchers(HttpMethod.POST, "/api/v1/fraud-flags/*/review").hasAuthority("FRAUD_REVIEW")
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").hasAnyRole("ADMIN", "AUDITOR")
                        .anyRequest().authenticated());
        http.addFilterBefore(new SessionAuthenticationFilter(authenticators, writer), CsrfFilter.class)
                .addFilterBefore(new RequestBodyLimitFilter(properties.security().http().maxRequestBodyBytes(), writer),
                        SessionAuthenticationFilter.class)
                .addFilterAfter(new RequestRateLimitFilter(limiter, properties, writer), SessionAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        List<String> origins = properties.security().corsAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            throw new IllegalArgumentException("CORS requires explicit HTTP origins");
        }
        for (String origin : origins) {
            try {
                URI uri = URI.create(origin);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || origin.contains("*")) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("CORS requires explicit HTTP origins");
            }
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID",
                "Idempotency-Key", "X-CSRF-Token", "X-Step-Up-Challenge"));
        configuration.setExposedHeaders(List.of("X-Correlation-ID", "X-Request-ID", "Idempotent-Replayed",
                "Retry-After", "Content-Disposition", "X-Export-SHA256", "X-Export-Audit-Id"));
        configuration.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public CorsFilter corsFilter(@Qualifier("corsConfigurationSource") CorsConfigurationSource source, ProblemWriter writer) {
        var filter = new CorsFilter(source);
        filter.setCorsProcessor(new DefaultCorsProcessor() {
            @Override
            protected void rejectRequest(ServerHttpResponse response) {
                // Replace the default plain-text CORS rejection with the shared Problem writer below.
            }

            @Override
            public boolean processRequest(CorsConfiguration configuration, HttpServletRequest request,
                    HttpServletResponse response) throws IOException {
                boolean allowed = super.processRequest(configuration, request, response);
                if (!allowed) {
                    problem(writer, request, response, ErrorCode.AUTH_FORBIDDEN);
                }
                return allowed;
            }
        });
        return filter;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsServletRegistration(CorsFilter corsFilter) {
        var registration = new FilterRegistrationBean<>(corsFilter);
        registration.setEnabled(false); // Run only once, inside Spring Security after the outer trace filter.
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiSecurityHeadersFilter> apiSecurityHeadersFilter() {
        var registration = new FilterRegistrationBean<>(new ApiSecurityHeadersFilter());
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 2);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public WebSecurityCustomizer rejectedRequestHandler(ProblemWriter writer) {
        return web -> web.requestRejectedHandler((request, response, exception) ->
                problem(writer, request, response, ErrorCode.MALFORMED_REQUEST));
    }

    private static void problem(ProblemWriter writer, HttpServletRequest request, HttpServletResponse response,
            ErrorCode code) throws IOException {
        writer.write(response, writer.create(code, code.detail(), request, List.of(), Map.of()));
    }
}
