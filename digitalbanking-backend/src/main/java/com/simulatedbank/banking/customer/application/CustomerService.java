package com.simulatedbank.banking.customer.application;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.crypto.PiiMaskingUtils;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQueryPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "banking.modules.enabled", havingValue = "true", matchIfMissing = true)
public class CustomerService implements CustomerQueryPort {
    private final JdbcTemplate jdbc;
    private final PiiCryptoService crypto;
    private final HmacService hmac;

    public CustomerService(JdbcTemplate jdbc, PiiCryptoService crypto, HmacService hmac) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.hmac = hmac;
    }

    @Transactional
    public long create(CreateCustomer command) {
        if (command == null || command.userId() <= 0 || command.cifNumber() == null
                || !command.cifNumber().matches("[A-Z0-9]{3,32}")
                || command.fullName() == null || command.fullName().isBlank() || command.fullName().length() > 150
                || !Set.of("PENDING", "VERIFIED", "REJECTED").contains(command.kycStatus())) {
            throw validation();
        }
        String nationalId = normalizeNationalId(command.nationalId());
        validateContact(command.phone(), command.email());
        String userType;
        try {
            userType = jdbc.queryForObject("SELECT user_type FROM banking.app_user WHERE user_id = ?",
                    String.class, command.userId());
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (!"CUSTOMER".equals(userType)) {
            throw new BankingBusinessException(ErrorCode.ROLE_CONFLICT);
        }
        try {
            return jdbc.queryForObject("""
                    INSERT INTO banking.customer(user_id, cif_number, full_name_enc, phone_enc, email_enc,
                        national_id_enc, national_id_hash, national_id_last4, kyc_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING customer_id
                    """, Long.class, command.userId(), command.cifNumber(), crypto.encrypt(command.fullName()),
                    encryptNullable(command.phone()), encryptNullable(command.email()), crypto.encrypt(nationalId),
                    hmac.hmacSha256Hex(nationalId), nationalId.substring(nationalId.length() - 4),
                    command.kycStatus());
        } catch (DataIntegrityViolationException conflict) {
            if (hasSqlState(conflict, "23505")) {
                throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
            }
            throw conflict;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerSnapshot getCustomerByUserId(long userId) {
        requirePositiveId(userId);
        try {
            return jdbc.queryForObject("""
                    SELECT customer_id, user_id, cif_number, full_name_enc, kyc_status, status
                    FROM banking.customer WHERE user_id = ?
                    """, this::snapshot, userId);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerSnapshot getCustomerById(long customerId) {
        requirePositiveId(customerId);
        try {
            return jdbc.queryForObject("""
                    SELECT customer_id, user_id, cif_number, full_name_enc, kyc_status, status
                    FROM banking.customer WHERE customer_id = ?
                    """, this::snapshot, customerId);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    public CustomerProfile selfProfile(SessionAuthenticator.Session actor) {
        requireAuthority(actor, "ROLE_CUSTOMER");
        long userId = actorId(actor.userId());
        long customerId = actorId(actor.customerId());
        CustomerRow row = findByUserId(userId);
        if (row.customerId() != customerId) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return profile(row, true);
    }

    @Transactional(readOnly = true)
    public CustomerProfile staffProfile(SessionAuthenticator.Session actor, long customerId) {
        requireStaffAuthority(actor, "CUSTOMER_READ");
        requirePositiveId(customerId);
        throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Staff customer data scope is not configured.");
    }

    @Override
    @Transactional(readOnly = true)
    public void assertCustomerActiveAndKycVerified(long customerId) {
        CustomerSnapshot customer = getCustomerById(customerId);
        if (!"ACTIVE".equals(customer.status()) || !"VERIFIED".equals(customer.kycStatus())) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private CustomerRow findByUserId(long userId) {
        try {
            return jdbc.queryForObject("""
                    SELECT customer_id, user_id, cif_number, full_name_enc, phone_enc, email_enc,
                           national_id_last4, kyc_status, status, created_at, updated_at
                    FROM banking.customer WHERE user_id = ?
                    """, this::row, userId);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
    }


    private CustomerProfile profile(CustomerRow row, boolean self) {
        String fullName = crypto.decrypt(row.fullNameEncrypted());
        return new CustomerProfile(Long.toString(row.customerId()), Long.toString(row.userId()),
                self ? fullName : maskName(fullName), maskPhone(row.phoneEncrypted()), maskEmail(row.emailEncrypted()),
                row.status(), row.createdAt(), row.updatedAt());
    }

    private CustomerSnapshot snapshot(ResultSet rs, int rowNum) throws SQLException {
        return new CustomerSnapshot(rs.getLong("customer_id"), rs.getLong("user_id"), rs.getString("cif_number"),
                crypto.decrypt(rs.getBytes("full_name_enc")), rs.getString("kyc_status"), rs.getString("status"));
    }

    private CustomerRow row(ResultSet rs, int rowNum) throws SQLException {
        return new CustomerRow(rs.getLong("customer_id"), rs.getLong("user_id"), rs.getString("cif_number"),
                rs.getBytes("full_name_enc"), rs.getBytes("phone_enc"), rs.getBytes("email_enc"),
                rs.getString("national_id_last4"), rs.getString("kyc_status"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private byte[] encryptNullable(String value) {
        return value == null ? null : crypto.encrypt(value);
    }

    private String maskPhone(byte[] encrypted) {
        return encrypted == null ? null : PiiMaskingUtils.maskPhone(crypto.decrypt(encrypted));
    }

    private String maskEmail(byte[] encrypted) {
        return encrypted == null ? null : PiiMaskingUtils.maskEmail(crypto.decrypt(encrypted));
    }

    private static void requireAuthority(SessionAuthenticator.Session actor, String authority) {
        if (actor == null || !actor.authorities().contains(authority)) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireStaffAuthority(SessionAuthenticator.Session actor, String authority) {
        requireAuthority(actor, authority);
        if (actor.authorities().contains("ROLE_CUSTOMER")
                || actor.authorities().stream().noneMatch(
                        Set.of("ROLE_OPERATIONS", "ROLE_ADMIN", "ROLE_AUDITOR")::contains)) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }
    static String normalizeNationalId(String value) {
        if (value == null) {
            throw validation();
        }
        String normalized = value.replace(" ", "").replace("-", "");
        if (!normalized.matches("[0-9]{9,12}")) {
            throw validation();
        }
        return normalized;
    }

    private static void validateContact(String phone, String email) {
        try {
            if (phone != null) PiiMaskingUtils.maskPhone(phone);
            if (email != null) PiiMaskingUtils.maskEmail(email);
        } catch (IllegalArgumentException invalid) {
            throw validation();
        }
    }

    private static long actorId(String value) {
        if (value == null) throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requirePositiveId(long value) {
        if (value <= 0) throw validation();
    }

    private static BankingBusinessException validation() {
        return new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
    }

    private static boolean hasSqlState(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && expected.equals(sql.getSQLState())) return true;
        }
        return false;
    }

    static String maskName(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length < 2) {
            return parts[0].substring(0, 1) + "***";
        }
        StringBuilder masked = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length - 1; index++) {
            masked.append(' ').append(parts[index].charAt(0)).append("***");
        }
        return masked.append(' ').append(parts[parts.length - 1]).toString();
    }

    public record CreateCustomer(long userId, String cifNumber, String fullName, String phone, String email,
            String nationalId, String kycStatus) {
    }

    public record CustomerProfile(String customerId, String userId, String fullName,
            String phoneMasked, String emailMasked, String status, Instant createdAt, Instant updatedAt) {
    }

    private record CustomerRow(long customerId, long userId, String cifNumber, byte[] fullNameEncrypted,
            byte[] phoneEncrypted, byte[] emailEncrypted, String nationalIdLast4, String kycStatus, String status,
            Instant createdAt, Instant updatedAt) {
    }
}
