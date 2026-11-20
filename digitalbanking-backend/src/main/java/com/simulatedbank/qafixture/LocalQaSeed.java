package com.simulatedbank.qafixture;

import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.identity.application.PasswordHasher;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.qa.seed", havingValue = "true")
public class LocalQaSeed {
    private record Actor(long id, String username, String type, String role) {}

    private static final Actor SENDER = new Actor(9000000000000011L, "qa-sender", "CUSTOMER", "CUSTOMER");
    private static final Actor RECEIVER = new Actor(9000000000000012L, "qa-receiver", "CUSTOMER", "CUSTOMER");
    private static final Actor MAKER = new Actor(9000000000000021L, "qa-maker", "STAFF", "OPERATIONS");
    private static final Actor ADMIN = new Actor(9000000000000022L, "qa-admin", "STAFF", "ADMIN");
    private static final Actor CHECKER = new Actor(9000000000000023L, "qa-checker", "STAFF", "ADMIN");
    private static final Actor AUDITOR = new Actor(9000000000000024L, "qa-auditor", "STAFF", "AUDITOR");
    private static final List<Actor> ACTORS = List.of(SENDER, RECEIVER, MAKER, ADMIN, CHECKER, AUDITOR);

    @Bean
    ApplicationRunner loadQaFixtures(Environment environment, JdbcTemplate jdbc, TransactionTemplate transactions,
            PasswordHasher passwords, PiiCryptoService encryption, HmacService hmac,
            @Value("${APP_QA_PASSWORD:}") String password) {
        if (!Arrays.equals(environment.getActiveProfiles(), new String[]{"local"}) || password.length() < 16) {
            throw new IllegalStateException("QA fixtures require explicit local profile and a QA password of at least 16 characters");
        }
        return args -> transactions.executeWithoutResult(tx -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('local-qa-fixtures', 0))");
            var existing = jdbc.query("""
                    SELECT user_id, username FROM banking.app_user WHERE username <> '__fraud_rule_bootstrap__'
                    """, (row, index) -> new UserIdentity(row.getLong("user_id"), row.getString("username")));
            if (existing.size() == ACTORS.size() && ACTORS.stream().allMatch(actor ->
                    existing.contains(new UserIdentity(actor.id(), actor.username())))) {
                return;
            }
            if (!existing.isEmpty()) throw new IllegalStateException("QA fixtures require a fresh or previously seeded QA database");

            for (Actor actor : ACTORS) {
                char[] plaintext = password.toCharArray();
                try {
                    jdbc.update("""
                            INSERT INTO banking.app_user(user_id, username, password_hash, user_type)
                            OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?)
                            """, actor.id(), actor.username(), passwords.hash(plaintext), actor.type());
                } finally {
                    Arrays.fill(plaintext, '\0');
                }
                jdbc.update("""
                        INSERT INTO banking.user_role(user_id, role_id)
                        SELECT ?, role_id FROM banking.role WHERE role_code = ?
                        """, actor.id(), actor.role());
            }
            jdbc.update("""
                    INSERT INTO banking.user_privilege(user_id, privilege_code, granted_by) VALUES (?, 'CHECKER', ?)
                    """, CHECKER.id(), ADMIN.id());
            seedCustomer(jdbc, encryption, hmac, SENDER, "0900000011", "000000000011", 50000000);
            seedCustomer(jdbc, encryption, hmac, RECEIVER, "0900000012", "000000000012", 0);
        });
    }

    private static void seedCustomer(JdbcTemplate jdbc, PiiCryptoService encryption, HmacService hmac,
            Actor actor, String phone, String accountNumber, long balance) {
        long customerId = jdbc.queryForObject("""
                INSERT INTO banking.customer(user_id, full_name_enc, phone_enc, transfer_sms_allowed)
                VALUES (?, ?, ?, true) RETURNING customer_id
                """, Long.class, actor.id(), encryption.encrypt(actor.username()), encryption.encrypt(phone));
        jdbc.update("""
                INSERT INTO banking.account(customer_id, account_no_enc, account_no_hash, account_no_last4,
                                            available_balance, ledger_balance)
                VALUES (?, ?, ?, ?, ?, ?)
                """, customerId, encryption.encrypt(accountNumber), hmac.hmacSha256Hex(accountNumber),
                accountNumber.substring(accountNumber.length() - 4), balance, balance);
        jdbc.update("""
                INSERT INTO banking.limit_config(customer_id, per_transaction_limit, daily_limit, effective_from,
                       status, version, created_by, approved_by, approved_at)
                VALUES (?, 20000000, 50000000, clock_timestamp(), 'ACTIVE', 1, ?, ?, clock_timestamp())
                """, customerId, MAKER.id(), CHECKER.id());
    }

    private record UserIdentity(long id, String username) {}
}
