package com.simulatedbank.banking.admin.application;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Service-level authorization re-check for Admin approval operations (CON-01 §8, BE-ADM-SEC-01 dependency).
 *
 * <p>The actor is always the trusted {@link SessionAuthenticator.Session} principal installed by
 * {@code SessionAuthenticationFilter}; a {@code checkerId}/{@code makerId} in a request body/header is never
 * trusted. The {@code CHECKER} privilege is required for decisions and for issuing a decision challenge; a
 * missing/expired session fails closed with {@code AUTH_SESSION_EXPIRED}, a missing permission with
 * {@code AUTH_FORBIDDEN} (both catalog codes of 07 §4.3). HTTP-level denial auditing stays with the shared
 * security chain ({@code SecurityAccessDeniedAuditor}); Identity re-checks the privilege from the database when
 * it applies a role change (design/05 §5.3, DEC-ID-011).</p>
 */
@Component
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true", matchIfMissing = true)
public class AdminAuthorization {

    static final String CHECKER_AUTHORITY = "CHECKER";

    /** Authenticated principal snapshot; never null and never a raw token. */
    public SessionAuthenticator.Session requireSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionAuthenticator.Session session)) {
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        return session;
    }

    /** Authenticated user id of the caller; the only accepted source of {@code checkerId}. */
    public long requireChecker() {
        return requireAuthority(CHECKER_AUTHORITY);
    }

    /** Requires one exact authority from the trusted snapshot and returns the authenticated actor id. */
    public long requireAuthority(String authority) {
        return requireAnyAuthority(authority);
    }

    /**
     * Requires at least one of the supplied authorities (for example {@code LIMIT_MAKER} or
     * {@code LIMIT_MANAGE}) and returns the authenticated actor id.
     */
    public long requireAnyAuthority(String... authorities) {
        if (authorities == null || authorities.length == 0) {
            throw new IllegalArgumentException("At least one authority is required");
        }
        SessionAuthenticator.Session session = requireSession();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean granted = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> Arrays.asList(authorities).contains(grantedAuthority.getAuthority()));
        if (!granted) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return userId(session);
    }

    private static long userId(SessionAuthenticator.Session session) {
        try {
            long userId = Long.parseLong(session.userId());
            if (userId <= 0) {
                throw new NumberFormatException();
            }
            return userId;
        } catch (NumberFormatException invalidSession) {
            // A malformed trusted snapshot is an authentication failure, not a client validation error.
            throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
    }
}
