package com.bank.simulator.identity.infrastructure.persistence;

import com.bank.simulator.customer.infrastructure.AccountNumberGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
public class IdentityJdbcRepository {

    private final JdbcTemplate jdbc;

    public IdentityJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createUser(UUID userId, String phone, String email, String passwordHash, String role, Instant now) {
        jdbc.update("INSERT INTO users (id, phone, email_normalized, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, phone, email, passwordHash, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO user_roles (user_id, role, created_at) VALUES (?, ?, ?)", userId, role, Timestamp.from(now));
    }

    public void createCustomer(UUID customerId, UUID userId, String fullName, String address, Instant now) {
        jdbc.update("INSERT INTO customers (id, user_id, full_name, address, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                customerId, userId, fullName.trim(), address, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO customer_pins (customer_id, updated_at) VALUES (?, ?)", customerId, Timestamp.from(now));
    }

    public AccountRecord createDefaultAccount(UUID customerId, AccountNumberGenerator accountNumbers, Instant now) {
        for (int attempt = 0; attempt < 5; attempt++) {
            UUID accountId = UUID.randomUUID();
            String accountNumber = accountNumbers.generate();
            try {
                jdbc.update("INSERT INTO accounts (id, account_number, customer_id, account_type, balance, currency, status, is_default, opened_at, updated_at) VALUES (?, ?, ?, 'CHECKING', 0, 'VND', 'ACTIVE', TRUE, ?, ?)",
                        accountId, accountNumber, customerId, Timestamp.from(now), Timestamp.from(now));
                return new AccountRecord(accountId, accountNumber, "CHECKING", "ACTIVE", "0", "VND", now);
            } catch (DuplicateKeyException exception) {
                if (attempt == 4) throw exception;
            }
        }
        throw new IllegalStateException("Unable to allocate account number");
    }

    public UserRecord findByPhone(String phone) {
        List<UserRecord> rows = jdbc.query("SELECT u.id, u.phone, u.email_normalized, u.password_hash, u.is_active, c.id AS customer_id, c.full_name, c.created_at AS customer_created_at, EXISTS (SELECT 1 FROM customer_pins p WHERE p.customer_id = c.id AND p.pin_hash IS NOT NULL) AS pin_set FROM users u LEFT JOIN customers c ON c.user_id = u.id WHERE u.phone = ?",
                (rs, row) -> new UserRecord(rs.getObject("id", UUID.class), rs.getString("phone"), rs.getString("email_normalized"), rs.getString("password_hash"), rs.getBoolean("is_active"), rs.getObject("customer_id", UUID.class), rs.getString("full_name"), rs.getTimestamp("customer_created_at") == null ? null : rs.getTimestamp("customer_created_at").toInstant(), rs.getBoolean("pin_set")), phone);
        return rows.stream().findFirst().orElse(null);
    }

    public List<String> roles(UUID userId) {
        return jdbc.queryForList("SELECT role FROM user_roles WHERE user_id = ? ORDER BY role", String.class, userId);
    }

    public UserRecord findByIdentifier(String identifier, String channel) {
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        String column = "SMS".equals(channel) ? "phone" : "email_normalized";
        List<UserRecord> rows = jdbc.query("SELECT u.id, u.phone, u.email_normalized, u.password_hash, u.is_active, c.id AS customer_id, c.full_name, c.created_at AS customer_created_at, EXISTS (SELECT 1 FROM customer_pins p WHERE p.customer_id = c.id AND p.pin_hash IS NOT NULL) AS pin_set FROM users u LEFT JOIN customers c ON c.user_id = u.id WHERE u." + column + " = ?",
                (rs, row) -> new UserRecord(rs.getObject("id", UUID.class), rs.getString("phone"), rs.getString("email_normalized"), rs.getString("password_hash"), rs.getBoolean("is_active"), rs.getObject("customer_id", UUID.class), rs.getString("full_name"), rs.getTimestamp("customer_created_at") == null ? null : rs.getTimestamp("customer_created_at").toInstant(), rs.getBoolean("pin_set")), normalized);
        return rows.stream().findFirst().orElse(null);
    }

    public CustomerRecord findCustomerById(UUID customerId) {
        List<CustomerRecord> rows = jdbc.query("SELECT c.id, c.user_id, c.full_name, c.address, c.created_at, u.phone, u.email_normalized, EXISTS (SELECT 1 FROM customer_pins p WHERE p.customer_id = c.id AND p.pin_hash IS NOT NULL) AS pin_set FROM customers c JOIN users u ON u.id = c.user_id WHERE c.id = ?",
                (rs, row) -> new CustomerRecord(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("full_name"), rs.getString("address"), rs.getTimestamp("created_at").toInstant(), rs.getString("phone"), rs.getString("email_normalized"), rs.getBoolean("pin_set")), customerId);
        return rows.stream().findFirst().orElse(null);
    }

    public CustomerRecord findCustomerByUserId(UUID userId) {
        List<CustomerRecord> rows = jdbc.query("SELECT c.id, c.user_id, c.full_name, c.address, c.created_at, u.phone, u.email_normalized, EXISTS (SELECT 1 FROM customer_pins p WHERE p.customer_id = c.id AND p.pin_hash IS NOT NULL) AS pin_set FROM customers c JOIN users u ON u.id = c.user_id WHERE c.user_id = ?",
                (rs, row) -> new CustomerRecord(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("full_name"), rs.getString("address"), rs.getTimestamp("created_at").toInstant(), rs.getString("phone"), rs.getString("email_normalized"), rs.getBoolean("pin_set")), userId);
        return rows.stream().findFirst().orElse(null);
    }


    public void updatePassword(UUID userId, String hash) {
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", hash, userId);
    }

    public PinRecord lockPin(UUID customerId) {
        List<PinRecord> rows = jdbc.query("SELECT pin_hash, failed_attempts, locked_until FROM customer_pins WHERE customer_id = ? FOR UPDATE",
                (rs, row) -> new PinRecord(rs.getString("pin_hash"), rs.getInt("failed_attempts"), rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant()), customerId);
        return rows.stream().findFirst().orElse(null);
    }

    public void setPin(UUID customerId, String hash, Instant now) {
        jdbc.update("UPDATE customer_pins SET pin_hash = ?, configured_at = ?, failed_attempts = 0, locked_until = NULL, updated_at = ? WHERE customer_id = ?",
                hash, Timestamp.from(now), Timestamp.from(now), customerId);
    }

    public void updatePinFailure(UUID customerId, int attempts, Instant lockedUntil, Instant now) {
        jdbc.update("UPDATE customer_pins SET failed_attempts = ?, locked_until = ?, updated_at = ? WHERE customer_id = ?",
                attempts, lockedUntil == null ? null : Timestamp.from(lockedUntil), Timestamp.from(now), customerId);
    }

    public List<CustomerRecord> findByPhoneOrEmail(String phone, String email) {
        String where = phone != null ? "u.phone = ?" : "u.email_normalized = ?";
        Object query = phone != null ? phone : email.trim().toLowerCase(Locale.ROOT);
        return jdbc.query("SELECT c.id, c.user_id, c.full_name, c.address, c.created_at, u.phone, u.email_normalized, EXISTS (SELECT 1 FROM customer_pins p WHERE p.customer_id = c.id AND p.pin_hash IS NOT NULL) AS pin_set FROM customers c JOIN users u ON u.id = c.user_id WHERE " + where,
                (rs, row) -> new CustomerRecord(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("full_name"), rs.getString("address"), rs.getTimestamp("created_at").toInstant(), rs.getString("phone"), rs.getString("email_normalized"), rs.getBoolean("pin_set")), query);
    }

    public List<AccountRecord> accounts(UUID customerId) {
        return jdbc.query("SELECT id, account_number, account_type, status, balance::text AS balance, currency, opened_at FROM accounts WHERE customer_id = ? ORDER BY opened_at, id",
                (rs, row) -> new AccountRecord(rs.getObject("id", UUID.class), rs.getString("account_number"), rs.getString("account_type"), rs.getString("status"), rs.getString("balance"), rs.getString("currency"), rs.getTimestamp("opened_at").toInstant()), customerId);
    }

    public record UserRecord(UUID userId, String phone, String email, String passwordHash, boolean active,
                             UUID customerId, String fullName, Instant createdAt, boolean pinSet) {}
    public record CustomerRecord(UUID customerId, UUID userId, String fullName, String address, Instant createdAt,
                                 String phone, String email, boolean pinSet) {}
    public record PinRecord(String pinHash, int failedAttempts, Instant lockedUntil) {}
    public record AccountRecord(UUID accountId, String accountNumber, String accountType, String status,
                                String balance, String currency, Instant openedAt) {
        public String maskedNumber() { return "••••" + accountNumber.substring(accountNumber.length() - 4); }
    }
}
