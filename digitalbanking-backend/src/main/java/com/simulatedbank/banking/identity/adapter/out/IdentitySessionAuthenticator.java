package com.simulatedbank.banking.identity.adapter.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.identity.application.AuthorityReader;
import com.simulatedbank.banking.identity.application.SessionRepository;
import com.simulatedbank.banking.identity.application.UserAccountRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Triển khai SPI `common.config.SessionAuthenticator` (design/05 §5.1: identity implement,
 * không tạo interface trùng). Validate token → hash → session ACTIVE chưa hết hạn →
 * user ACTIVE → snapshot authorities; gia hạn idle trong cùng statement (fail-closed).
 * Empty khi token/expiry/revoke/user không hợp lệ → filter trả `401 AUTH_SESSION_EXPIRED`.
 */
@Component
@ConditionalOnProperty(name = "app.identity.enabled", havingValue = "true", matchIfMissing = true)
public class IdentitySessionAuthenticator implements SessionAuthenticator {

    private static final Logger LOG = LoggerFactory.getLogger(IdentitySessionAuthenticator.class);

    private final SessionRepository sessions;
    private final UserAccountRepository users;
    private final AuthorityReader authorities;
    private final long idleTimeoutSeconds;

    public IdentitySessionAuthenticator(SessionRepository sessions, UserAccountRepository users,
            AuthorityReader authorities, com.simulatedbank.banking.common.config.AppProperties properties) {
        this.sessions = sessions;
        this.users = users;
        this.authorities = authorities;
        this.idleTimeoutSeconds = java.time.Duration
                .ofMinutes(properties.security().session().idleTimeoutMinutes()).toSeconds();
    }

    /**
     * Fail-closed: mọi lỗi đọc session/user/authority (DB không khả dụng, connection/transaction
     * failure) KHÔNG được cấp phiên — map `503 SERVICE_UNAVAILABLE` (07 §8.1.1/§8.1.6, DEC-ID-003
     * mục 7) để `SessionAuthenticationFilter` viết Problem chuẩn, không rò rỉ internals.
     */
    @Override
    public Optional<Session> authenticate(String opaqueToken) {
        try {
            return doAuthenticate(opaqueToken);
        } catch (org.springframework.dao.DataAccessException
                | org.springframework.transaction.CannotCreateTransactionException storageFailure) {
            LOG.warn("Session rejected: storage failure");
            throw new com.simulatedbank.banking.common.error.BankingBusinessException(
                    com.simulatedbank.banking.common.error.ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private Optional<Session> doAuthenticate(String opaqueToken) {
        String tokenHash = com.simulatedbank.banking.common.crypto.HmacService.sha256Hex(opaqueToken);
        Optional<SessionRepository.ActiveSession> active = sessions.validateAndRefresh(tokenHash,
                idleTimeoutSeconds);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        long userId = active.get().userId();
        Optional<UserAccountRepository.AppUserSnapshot> user = users.findById(userId);
        if (user.isEmpty() || !"ACTIVE".equals(user.get().status())) {
            LOG.warn("Session rejected: owner not active");
            return Optional.empty();
        }
        return Optional.of(new Session(Long.toString(userId), null, authorities.loadAuthorities(userId),
                active.get().csrfTokenHash()));
    }
}
