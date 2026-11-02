package com.bank.simulator.identity.application;

import com.bank.simulator.audit.api.AuditWriter;
import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository;
import com.bank.simulator.identity.infrastructure.security.PasswordHashingService;
import com.bank.simulator.identity.infrastructure.security.PinHashingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private final IdentityJdbcRepository repository;
    private final PasswordHashingService passwords;
    private final PinHashingService pins;
    private final AccountNumberGenerator accountNumbers;
    private final AuditWriter auditWriter;
    private final Clock clock;
    private final Credentials operator;
    private final Credentials auditor;
    private final Credentials customerOne;
    private final Credentials customerTwo;

    public DemoDataSeeder(IdentityJdbcRepository repository, PasswordHashingService passwords,
                          PinHashingService pins, AccountNumberGenerator accountNumbers, AuditWriter auditWriter,
                          Clock clock,
                          @Value("${app.demo.operator.phone:}") String operatorPhone,
                          @Value("${app.demo.operator.email:}") String operatorEmail,
                          @Value("${app.demo.operator.password:}") String operatorPassword,
                          @Value("${app.demo.auditor.phone:}") String auditorPhone,
                          @Value("${app.demo.auditor.email:}") String auditorEmail,
                          @Value("${app.demo.auditor.password:}") String auditorPassword,
                          @Value("${app.demo.customer-one.phone:}") String customerOnePhone,
                          @Value("${app.demo.customer-one.email:}") String customerOneEmail,
                          @Value("${app.demo.customer-one.password:}") String customerOnePassword,
                          @Value("${app.demo.customer-one.pin:}") String customerOnePin,
                          @Value("${app.demo.customer-two.phone:}") String customerTwoPhone,
                          @Value("${app.demo.customer-two.email:}") String customerTwoEmail,
                          @Value("${app.demo.customer-two.password:}") String customerTwoPassword,
                          @Value("${app.demo.customer-two.pin:}") String customerTwoPin) {
        this.repository = repository;
        this.passwords = passwords;
        this.pins = pins;
        this.accountNumbers = accountNumbers;
        this.auditWriter = auditWriter;
        this.clock = clock;
        this.operator = new Credentials(operatorPhone, operatorEmail, operatorPassword, null);
        this.auditor = new Credentials(auditorPhone, auditorEmail, auditorPassword, null);
        this.customerOne = new Credentials(customerOnePhone, customerOneEmail, customerOnePassword, customerOnePin);
        this.customerTwo = new Credentials(customerTwoPhone, customerTwoEmail, customerTwoPassword, customerTwoPin);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        validate(operator, auditor, customerOne, customerTwo);
        ensureEmailAvailable(operator);
        ensureEmailAvailable(auditor);
        ensureEmailAvailable(customerOne);
        ensureEmailAvailable(customerTwo);
        createStaff(operator, "OPERATOR", "Demo Operator");
        createStaff(auditor, "AUDITOR", "Demo Auditor");
        createCustomer(customerOne, "Demo Customer One");
        createCustomer(customerTwo, "Demo Customer Two");
    }

    private void validate(Credentials... credentials) {
        for (Credentials credential : credentials) {
            if (credential.phone().isBlank() || !credential.phone().matches("^0[3-9][0-9]{8}$")
                    || credential.email().isBlank() || !credential.email().contains("@")
                    || credential.password().length() < 12
                    || (credential.pin() != null && !credential.pin().matches("^[0-9]{6}$"))) {
                throw new IllegalStateException("Demo seed credentials are missing or invalid");
            }
        }
        long distinctPhones = java.util.Arrays.stream(credentials).map(Credentials::phone).distinct().count();
        long distinctEmails = java.util.Arrays.stream(credentials).map(Credentials::email)
                .map(value -> value.toLowerCase(Locale.ROOT)).distinct().count();
        if (distinctPhones != credentials.length || distinctEmails != credentials.length) {
            throw new IllegalStateException("Demo seed identities must have distinct phone and email values");
        }
    }

    private void ensureEmailAvailable(Credentials credentials) {
        if (repository.findByIdentifier(credentials.email(), "EMAIL") != null
                && repository.findByPhone(credentials.phone()) == null) {
            throw new IllegalStateException("Demo seed email belongs to a different identity");
        }
    }

    private void createStaff(Credentials credentials, String role, String name) {
        if (repository.findByPhone(credentials.phone()) != null) return;
        UUID userId = UUID.randomUUID();
        repository.createUser(userId, credentials.phone(), normalize(credentials.email()),
                passwords.encode(credentials.password()), role, clock.instant());
        auditWriter.record(null, null, "DEMO_PRINCIPAL_SEEDED", "USER", userId,
                "SUCCESS", null, "Demo " + role.toLowerCase(Locale.ROOT) + " principal created.");
    }

    private void createCustomer(Credentials credentials, String name) {
        var existing = repository.findByPhone(credentials.phone());
        if (existing != null) return;
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        var now = clock.instant();
        repository.createUser(userId, credentials.phone(), normalize(credentials.email()),
                passwords.encode(credentials.password()), "CUSTOMER", now);
        repository.createCustomer(customerId, userId, name, null, now);
        repository.createDefaultAccount(customerId, accountNumbers, now);
        repository.setPin(customerId, pins.encode(credentials.pin()), now);
        auditWriter.record(null, null, "DEMO_CUSTOMER_SEEDED", "CUSTOMER", customerId,
                "SUCCESS", null, "Demo customer and default account created.");
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record Credentials(String phone, String email, String password, String pin) {}
}
