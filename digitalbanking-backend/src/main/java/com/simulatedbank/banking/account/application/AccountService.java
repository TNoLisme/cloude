package com.simulatedbank.banking.account.application;

import com.simulatedbank.banking.account.api.AccountBalancePort;
import com.simulatedbank.banking.account.api.AccountQueryPort;
import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.crypto.HmacService;
import com.simulatedbank.banking.common.crypto.PiiCryptoService;
import com.simulatedbank.banking.common.crypto.PiiMaskingUtils;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQueryPort;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "banking.modules.enabled", havingValue = "true", matchIfMissing = true)
public class AccountService implements AccountQueryPort, AccountBalancePort {
    private final JdbcTemplate jdbc;
    private final PiiCryptoService crypto;
    private final HmacService hmac;
    private final CustomerQueryPort customers;
    private final Clock clock;

    public AccountService(JdbcTemplate jdbc, PiiCryptoService crypto, HmacService hmac,
            CustomerQueryPort customers, Clock clock) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.hmac = hmac;
        this.customers = customers;
        this.clock = clock;
    }

    @Transactional
    public long create(CreateAccount command) {
        if (command == null || command.customerId() <= 0
                || !Set.of("CHECKING", "SAVINGS").contains(command.accountType())
                || command.openingBalance() == null || command.openingBalance().compareTo(BigDecimal.ZERO) != 0
                || command.openingBalance().scale() > 2) {
            throw validation();
        }
        customers.assertCustomerActiveAndKycVerified(command.customerId());
        String accountNumber = normalizeAccountNumber(command.accountNumber());
        try {
            return jdbc.queryForObject("""
                    INSERT INTO banking.account(customer_id, account_no_enc, account_no_hash, account_no_last4,
                        account_type, available_balance, ledger_balance)
                    VALUES (?, ?, ?, ?, ?, 0, 0) RETURNING account_id
                    """, Long.class, command.customerId(), crypto.encrypt(accountNumber),
                    hmac.hmacSha256Hex(accountNumber), accountNumber.substring(accountNumber.length() - 4),
                    command.accountType());
        } catch (DataIntegrityViolationException conflict) {
            if (hasSqlState(conflict, "23505")) {
                throw new BankingBusinessException(ErrorCode.INVALID_STATE_TRANSITION);
            }
            throw conflict;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSnapshot getAccountOrThrow(long accountId) {
        requirePositiveId(accountId);
        try {
            return jdbc.queryForObject("""
                    SELECT account_id, customer_id, account_no_last4, account_type, currency, status,
                           available_balance, ledger_balance
                    FROM banking.account WHERE account_id = ?
                    """, this::snapshot, accountId);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSnapshot resolveByAccountNumber(String rawAccountNumber) {
        try {
            return jdbc.queryForObject("""
                    SELECT account_id, customer_id, account_no_last4, account_type, currency, status,
                           available_balance, ledger_balance
                    FROM banking.account WHERE account_no_hash = ?
                    """, this::snapshot, hmac.hmacSha256Hex(normalizeAccountNumber(rawAccountNumber)));
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void assertOwnership(long accountId, long customerId) {
        requirePositiveId(accountId);
        requirePositiveId(customerId);
        AccountSnapshot account = getAccountOrThrow(accountId);
        if (account.customerId() != customerId) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    @Transactional(readOnly = true)
    public AccountsResponse listOwn(SessionAuthenticator.Session actor, String status) {
        requireAuthority(actor, "ROLE_CUSTOMER");
        requireAuthority(actor, "ACCOUNT_READ_SELF");
        long userId = actorId(actor.userId());
        long customerId = actorId(actor.customerId());
        CustomerQueryPort.CustomerSnapshot customer = customers.getCustomerByUserId(userId);
        if (customer.customerId() != customerId) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return listByCustomer(customerId, status);
    }

    @Transactional(readOnly = true)
    public AccountsResponse listForStaff(SessionAuthenticator.Session actor, long customerId) {
        requireStaffAuthority(actor, "ACCOUNT_READ");
        requirePositiveId(customerId);
        throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Staff account data scope is not configured.");
    }

    private AccountsResponse listByCustomer(long customerId, String status) {
        if (status != null && !Set.of("ACTIVE", "LOCKED", "CLOSED").contains(status)) {
            throw validation();
        }
        Instant asOf = clock.instant();
        List<AccountSummary> items = status == null
                ? jdbc.query("""
                        SELECT account_id, account_no_last4, currency, status,
                               available_balance, ledger_balance
                        FROM banking.account
                        WHERE customer_id = ?
                        ORDER BY account_id
                        """, (rs, rowNum) -> summary(rs, asOf), customerId)
                : jdbc.query("""
                        SELECT account_id, account_no_last4, currency, status,
                               available_balance, ledger_balance
                        FROM banking.account
                        WHERE customer_id = ? AND status = ?
                        ORDER BY account_id
                        """, (rs, rowNum) -> summary(rs, asOf), customerId, status);
        return new AccountsResponse(items, asOf);
    }

    @Transactional(readOnly = true)
    public AccountDetail detail(SessionAuthenticator.Session actor, long accountId) {
        requirePositiveId(accountId);
        if (actor == null) throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        if (!actor.authorities().contains("ROLE_CUSTOMER")) {
            requireStaffAuthority(actor, "ACCOUNT_READ");
            throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Staff account data scope is not configured.");
        }
        requireAuthority(actor, "ACCOUNT_READ_SELF");
        long customerId = actorId(actor.customerId());
        CustomerQueryPort.CustomerSnapshot customer = customers.getCustomerByUserId(actorId(actor.userId()));
        if (customer.customerId() != customerId) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        AccountRecord record = findRecord(accountId);
        if (record.snapshot().customerId() != customerId) {
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        Instant asOf = clock.instant();
        AccountSummary summary = toSummary(record.snapshot(), asOf);
        return new AccountDetail(summary.accountId(), summary.accountNumberMasked(), summary.accountNumberLast4(),
                summary.currency(), summary.status(), summary.availableBalance(), summary.ledgerBalance(),
                summary.asOf(), new CustomerSummary(Long.toString(customer.customerId()), customer.fullName(),
                        customer.status()), record.createdAt(), record.updatedAt());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AccountSnapshot> lockTransferAccountsInOrder(long sourceAccountId, long destinationAccountId) {
        requirePositiveId(sourceAccountId);
        requirePositiveId(destinationAccountId);
        if (sourceAccountId == destinationAccountId) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        List<AccountSnapshot> rows = jdbc.query("""
                SELECT account_id, customer_id, account_no_last4, account_type, currency, status,
                       available_balance, ledger_balance
                FROM banking.account
                WHERE account_id IN (?, ?)
                ORDER BY account_id ASC
                FOR UPDATE
                """, this::snapshot, sourceAccountId, destinationAccountId);
        if (rows.size() != 2) {
            throw new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        return rows;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Balance debitConditional(long sourceAccountId, BigDecimal amount) {
        validateAmount(amount);
        throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Ledger posting integration is required before balance mutation.");
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Balance creditConditional(long destinationAccountId, BigDecimal amount) {
        validateAmount(amount);
        throw new BankingBusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Ledger posting integration is required before balance mutation.");
    }

    private AccountSnapshot snapshot(ResultSet rs, int rowNum) throws SQLException {
        return new AccountSnapshot(rs.getLong("account_id"), rs.getLong("customer_id"),
                rs.getString("account_no_last4"), rs.getString("account_type"), rs.getString("currency"),
                rs.getString("status"), rs.getBigDecimal("available_balance"), rs.getBigDecimal("ledger_balance"));
    }

    private AccountSummary summary(ResultSet rs, Instant asOf) throws SQLException {
        return new AccountSummary(Long.toString(rs.getLong("account_id")),
                PiiMaskingUtils.maskAccountLast4(rs.getString("account_no_last4")),
                rs.getString("account_no_last4"), rs.getString("currency"), rs.getString("status"),
                rs.getBigDecimal("available_balance").toPlainString(), rs.getBigDecimal("ledger_balance").toPlainString(),
                asOf);
    }

    private AccountSummary toSummary(AccountSnapshot account, Instant asOf) {
        return new AccountSummary(Long.toString(account.accountId()),
                PiiMaskingUtils.maskAccountLast4(account.accountNumberLast4()), account.accountNumberLast4(),
                account.currency(), account.status(), account.availableBalance().toPlainString(),
                account.ledgerBalance().toPlainString(), asOf);
    }


    static String normalizeAccountNumber(String value) {
        if (value == null) {
            throw validation();
        }
        String normalized = value.replace(" ", "").replace("-", "");
        if (!normalized.matches("[0-9]{6,20}")) {
            throw validation();
        }
        return normalized;
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
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

    private AccountRecord findRecord(long accountId) {
        try {
            return jdbc.queryForObject("""
                    SELECT account_id, customer_id, account_no_last4, account_type, currency, status,
                           available_balance, ledger_balance, created_at, updated_at
                    FROM banking.account WHERE account_id = ?
                    """, (rs, rowNum) -> new AccountRecord(snapshot(rs, rowNum),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("updated_at", OffsetDateTime.class).toInstant()), accountId);
        } catch (EmptyResultDataAccessException absent) {
            throw new BankingBusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
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



    public record CreateAccount(long customerId, String accountNumber, String accountType, BigDecimal openingBalance) {
    }
    public record AccountSummary(String accountId, String accountNumberMasked, String accountNumberLast4,
            String currency, String status, String availableBalance, String ledgerBalance, Instant asOf) {
    }
    public record AccountsResponse(List<AccountSummary> items, Instant asOf) {
        public AccountsResponse { items = List.copyOf(items); }
    }
    public record CustomerSummary(String customerId, String fullName, String status) {
    }
    public record AccountDetail(String accountId, String accountNumberMasked, String accountNumberLast4,
            String currency, String status, String availableBalance, String ledgerBalance,
            Instant asOf, CustomerSummary owner, Instant createdAt, Instant updatedAt) {
    }
    private record AccountRecord(AccountSnapshot snapshot, Instant createdAt, Instant updatedAt) {
    }
}
