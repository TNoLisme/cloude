package com.bank.simulator.identity.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import com.bank.simulator.identity.infrastructure.security.RefreshSessionService;
import com.bank.simulator.shared.error.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class OnboardingService {

    private static final String DUMMY_HASH = "$2a$12$C6UzMDM.H6dfI/f/IKcEe.VeY6QK7f3h8Y7l8J0Q3jz7wH9t0Q8u";

    private final IdentityJdbcRepository repository;
    private final OtpChallengeService otpService;
    private final PasswordHashingService passwordHashing;
    private final PinHashingService pinHashing;
    private final RefreshSessionService sessions;
    private final JwtAccessTokenCodec tokens;
    private final Clock clock;
    private final com.bank.simulator.customer.infrastructure.AccountNumberGenerator accountNumbers;
    private final PinCredentialService pinCredentials;
    private final ObjectProvider<AuditWriter> auditWriters;

    public OnboardingService(IdentityJdbcRepository repository, OtpChallengeService otpService,
                             PasswordHashingService passwordHashing, PinHashingService pinHashing,
                             RefreshSessionService sessions, JwtAccessTokenCodec tokens, Clock clock,
                             com.bank.simulator.customer.infrastructure.AccountNumberGenerator accountNumbers,
                             PinCredentialService pinCredentials) {
        this(repository, otpService, passwordHashing, pinHashing, sessions, tokens, clock,
                accountNumbers, pinCredentials, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OnboardingService(IdentityJdbcRepository repository, OtpChallengeService otpService,
                             PasswordHashingService passwordHashing, PinHashingService pinHashing,
                             RefreshSessionService sessions, JwtAccessTokenCodec tokens, Clock clock,
                             com.bank.simulator.customer.infrastructure.AccountNumberGenerator accountNumbers,
                             PinCredentialService pinCredentials, ObjectProvider<AuditWriter> auditWriters) {
        this.repository = repository;
        this.otpService = otpService;
        this.passwordHashing = passwordHashing;
        this.pinHashing = pinHashing;
        this.sessions = sessions;
        this.tokens = tokens;
        this.clock = clock;
        this.accountNumbers = accountNumbers;
        this.pinCredentials = pinCredentials;
        this.auditWriters = auditWriters;
    }

    public OtpChallengeService.OtpIssueResult sendRegistrationOtp(String phone) {
        return otpService.issue(phone, "SMS", "REGISTRATION");
    }

    @Transactional
    public RegistrationResult register(String phone, String email, String password, String fullName, String address, String otp) {
        requireOtp(phone, "SMS", "REGISTRATION", otp);
        return createVerifiedRegistration(phone, email, password, fullName, address);
    }

    // Package-only: caller must validate proof and hold the registration transaction.
    RegistrationResult createVerifiedRegistration(String phone, String email, String password, String fullName, String address) {
        String normalizedEmail = normalize(email);
        if (repository.findByPhone(phone) != null) conflict("PHONE_ALREADY_REGISTERED", "Phone number is already registered.");
        if (repository.findByIdentifier(normalizedEmail, "EMAIL") != null) conflict("EMAIL_ALREADY_REGISTERED", "Email address is already registered.");
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            repository.createUser(userId, phone, normalizedEmail, passwordHashing.encode(password), "CUSTOMER", now);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw duplicateIdentity(exception);
        }
        repository.createCustomer(customerId, userId, fullName, address, now);
        IdentityJdbcRepository.AccountRecord account = repository.createDefaultAccount(customerId, accountNumbers, now);
        audit(null, null, "CUSTOMER_REGISTERED", "CUSTOMER", customerId, "SUCCESS", "Customer registered with default account.");
        return new RegistrationResult(customerId, phone, normalizedEmail, fullName.trim(), account, now);
    }

    public LoginResult login(String phone, String password) {
        IdentityJdbcRepository.UserRecord user = repository.findByPhone(phone);
        if (user == null) {
            passwordHashing.matches(password, DUMMY_HASH);
            audit(null, null, "LOGIN", "USER", null, "FAILURE", "Login rejected.");
            invalidCredentials();
        }
        if (!user.active() || !passwordHashing.matches(password, user.passwordHash())) {
            audit(user.userId(), firstRole(user.userId()), "LOGIN", "USER", user.userId(), "FAILURE", "Login rejected.");
            invalidCredentials();
        }
        List<String> roles = repository.roles(user.userId());
        RefreshSessionService.IssuedRefreshSession session = sessions.issue(user.userId());
        String accessToken = tokens.issue(user.userId(), roles);
        audit(user.userId(), roles.stream().findFirst().orElse(null), "LOGIN", "USER", user.userId(), "SUCCESS", "User logged in.");
        return new LoginResult(accessToken, session, userSummary(user, roles));
    }

    @Transactional
    public RefreshResult refreshSession(String rawToken) {
        RefreshSessionService.IssuedRefreshSession session = sessions.rotate(rawToken);
        IdentityJdbcRepository.UserRecord user = repository.findByUserId(session.userId());
        if (user == null || !user.active()) throw new IllegalArgumentException("Refresh session is invalid");
        return new RefreshResult(session, userSummary(user, session.roles()));
    }

    private UserSummary userSummary(IdentityJdbcRepository.UserRecord user, List<String> roles) {
        UUID customerId = user.customerId() == null ? user.userId() : user.customerId();
        String displayName = user.fullName() == null ? user.phone() : user.fullName();
        return new UserSummary(user.userId(), customerId, displayName,
                user.phone(), user.email(), roles, user.pinSet());
    }

    public IdentityJdbcRepository.CustomerRecord currentCustomer(AuthenticatedActor actor) {
        IdentityJdbcRepository.CustomerRecord customer = repository.findCustomerByUserId(actor.userId());
        if (customer == null) throw new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer profile is not available.");
        return customer;
    }

    @Transactional
    public void setupPin(AuthenticatedActor actor, String pin) {
        IdentityJdbcRepository.CustomerRecord customer = currentCustomer(actor);
        IdentityJdbcRepository.PinRecord current = repository.lockPin(customer.customerId());
        if (current == null) throw new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer profile is not available.");
        if (current.pinHash() != null) conflict("PIN_ALREADY_SET", "Transaction PIN is already configured.");
        repository.setPin(customer.customerId(), pinHashing.encode(pin), clock.instant());
        audit(actor.userId(), actor.roles().stream().findFirst().orElse(null), "PIN_SETUP", "CUSTOMER", customer.customerId(), "SUCCESS", "Transaction PIN configured.");
    }

    public void changePin(AuthenticatedActor actor, String currentPin, String newPin) {
        IdentityJdbcRepository.CustomerRecord customer = currentCustomer(actor);
        PinCredentialService.ChangeResult result = pinCredentials.change(customer.customerId(), currentPin, newPin);
        if (result == PinCredentialService.ChangeResult.NOT_SET) conflict("PIN_NOT_SET", "Transaction PIN is not configured.");
        if (result == PinCredentialService.ChangeResult.LOCKED) throw new ApiException(HttpStatus.FORBIDDEN, "PIN_LOCKED", "Transaction PIN is temporarily locked.");
        if (result == PinCredentialService.ChangeResult.INVALID) throw new ApiException(HttpStatus.FORBIDDEN, "PIN_INVALID", "Transaction PIN is invalid.");
        audit(actor.userId(), actor.roles().stream().findFirst().orElse(null), "PIN_CHANGED", "CUSTOMER", customer.customerId(), "SUCCESS", "Transaction PIN changed.");
    }

    public OtpChallengeService.OtpIssueResult initiateForgotPin(AuthenticatedActor actor) {
        IdentityJdbcRepository.CustomerRecord customer = currentCustomer(actor);
        return otpService.issue(customer.phone(), "SMS", "PIN_RESET");
    }

    @Transactional
    public void confirmForgotPin(AuthenticatedActor actor, String otp, String newPin) {
        IdentityJdbcRepository.CustomerRecord customer = currentCustomer(actor);
        requireOtp(customer.phone(), "SMS", "PIN_RESET", otp);
        repository.setPin(customer.customerId(), pinHashing.encode(newPin), clock.instant());
        audit(actor.userId(), actor.roles().stream().findFirst().orElse(null), "PIN_RESET", "CUSTOMER", customer.customerId(), "SUCCESS", "Transaction PIN reset.");
    }

    public OtpChallengeService.OtpIssueResult sendOperatorOtp(AuthenticatedActor actor, String phone) {
        requireOperator(actor);
        return otpService.issue(phone, "SMS", "OPERATOR_CREATE_CUSTOMER");
    }

    @Transactional
    public RegistrationResult createAtCounter(AuthenticatedActor actor, String phone, String email, String fullName,
                                              String address, String initialPassword, String otp) {
        requireOperator(actor);
        requireOtp(phone, "SMS", "OPERATOR_CREATE_CUSTOMER", otp);
        if (repository.findByPhone(phone) != null) conflict("PHONE_ALREADY_REGISTERED", "Phone number is already registered.");
        String normalizedEmail = normalize(email);
        if (repository.findByIdentifier(normalizedEmail, "EMAIL") != null) conflict("EMAIL_ALREADY_REGISTERED", "Email address is already registered.");
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            repository.createUser(userId, phone, normalizedEmail, passwordHashing.encode(initialPassword), "CUSTOMER", now);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw duplicateIdentity(exception);
        }
        repository.createCustomer(customerId, userId, fullName, address, now);
        IdentityJdbcRepository.AccountRecord account = repository.createDefaultAccount(customerId, accountNumbers, now);
        audit(actor.userId(), actor.roles().stream().findFirst().orElse(null), "CUSTOMER_CREATED_AT_COUNTER", "CUSTOMER", customerId, "SUCCESS", "Customer created at counter.");
        return new RegistrationResult(customerId, phone, normalizedEmail, fullName.trim(), account, now);
    }

    public OperatorCustomerView lookup(AuthenticatedActor actor, String phone, String email) {
        requireOperator(actor);
        if ((phone == null) == (email == null)) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Exactly one lookup filter is required.");
        List<IdentityJdbcRepository.CustomerRecord> customers = repository.findByPhoneOrEmail(phone, email);
        if (customers.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer is not available.");
        IdentityJdbcRepository.CustomerRecord customer = customers.getFirst();
        audit(actor.userId(), actor.roles().stream().findFirst().orElse(null), "CUSTOMER_LOOKUP", "CUSTOMER", customer.customerId(), "SUCCESS", "Customer looked up at counter.");
        return new OperatorCustomerView(customer.customerId(), customer.fullName(), customer.phone(), customer.email(), customer.pinSet(), customer.createdAt(), repository.accounts(customer.customerId()));
    }

    private void audit(UUID actorId, String actorRole, String eventType, String targetType, UUID targetId, String outcome, String summary) {
        AuditWriter writer = auditWriters == null ? null : auditWriters.getIfAvailable();
        if (writer != null && targetId != null) {
            writer.record(actorId, actorRole, eventType, targetType, targetId, outcome,
                    parseCorrelationId(org.slf4j.MDC.get("correlationId")), summary);
        }
    }

    private UUID parseCorrelationId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String firstRole(UUID userId) {
        return repository.roles(userId).stream().findFirst().orElse(null);
    }

    private void requireOperator(AuthenticatedActor actor) {
        if (!actor.hasRole("OPERATOR") && !actor.hasRole("ADMIN")) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
    }

    private void requireOtp(String identifier, String channel, String purpose, String code) {
        if (requireOtpResult(identifier, channel, purpose, code) != OtpChallengeService.ConsumeResult.VALID) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP is invalid.");
        }
    }

    private OtpChallengeService.ConsumeResult requireOtpResult(String identifier, String channel, String purpose, String code) {
        return otpService.consumeLatest(identifier, channel, purpose, code);
    }

    private ApiException duplicateIdentity(org.springframework.dao.DuplicateKeyException exception) {
        String constraint = exception.getMostSpecificCause().getMessage();
        if (constraint != null && constraint.contains("uq_users_phone")) {
            return new ApiException(HttpStatus.CONFLICT, "PHONE_ALREADY_REGISTERED", "Phone number is already registered.");
        }
        if (constraint != null && constraint.contains("uq_users_email_normalized")) {
            return new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email address is already registered.");
        }
        return new ApiException(HttpStatus.CONFLICT, "IDENTITY_ALREADY_REGISTERED", "Identity is already registered.");
    }

    private String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private void invalidCredentials() { throw new ApiException(HttpStatus.UNAUTHORIZED, "CREDENTIALS_INVALID", "Phone or password is invalid."); }
    private void conflict(String code, String detail) { throw new ApiException(HttpStatus.CONFLICT, code, detail); }

    public record RegistrationResult(UUID customerId, String phone, String email, String fullName,
                                    IdentityJdbcRepository.AccountRecord account, Instant createdAt) {}
    public record LoginResult(String accessToken, RefreshSessionService.IssuedRefreshSession session, UserSummary user) {}
    public record RefreshResult(RefreshSessionService.IssuedRefreshSession session, UserSummary user) {}
    public record UserSummary(UUID userId, UUID customerId, String displayName, String phone, String email,
                              List<String> roles, boolean isPinSet) {}
    public record OperatorCustomerView(UUID customerId, String fullName, String phone, String email,
                                       boolean isPinSet, Instant createdAt, List<IdentityJdbcRepository.AccountRecord> accounts) {}
}
