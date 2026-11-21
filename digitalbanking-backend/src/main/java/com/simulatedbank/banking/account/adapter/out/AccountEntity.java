package com.simulatedbank.banking.account.adapter.out;

import com.simulatedbank.banking.account.application.AccountNumberProtection.ProtectedAccountNumber;
import com.simulatedbank.banking.account.domain.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Mapping persistence nội bộ của {@code banking.account} (BE-ACC-03).
 *
 * <p>Nguồn: {@code 04} §4.3.1, {@code V8__create_account_table.sql}, BE-ACC-02 §C; enum lưu dạng
 * {@code VARCHAR + CHECK} nên dùng {@link EnumType#STRING} (không ordinal). Không có cột
 * {@code version} → không optimistic locking (ACC-DR-07); row lock/conditional UPDATE thuộc
 * BE-ACC-06/07.</p>
 *
 * <p>Bảo mật: {@code account_number_encrypted} chỉ chứa AES-256-GCM blob do Common sinh, không có
 * plaintext; {@code accountNumberHash} là HMAC-SHA256 blind index. Entity không bao giờ được trả
 * qua {@code account.api}, log hay REST; {@code toString()} redacted.</p>
 *
 * <p>Không có factory/write operation public: account provisioning chưa được duyệt (ACC-DR-04
 * BLOCKED). Constructor package-private chỉ dùng cho verification và cho write use case tương lai
 * khi policy được approve.</p>
 */
@Entity
@Table(name = "account", schema = "banking")
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "account_no_enc", nullable = false, updatable = false)
    private byte[] accountNumberEncrypted;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_no_hash", nullable = false, updatable = false, length = 64)
    private String accountNumberHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_no_last4", nullable = false, updatable = false, length = 4)
    private String accountNumberLast4;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal availableBalance;

    @Column(name = "ledger_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal ledgerBalance;

    @Column(name = "closed_at")
    private Instant closedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountEntity() {
    }

    /**
     * Tạo row account mới với default canonical (VND, ACTIVE, balance 0, chưa đóng).
     *
     * @param protectedAccountNumber kết quả {@code AccountNumberProtection.protect}; không nhận
     *                               plaintext để tránh hai nguồn dữ liệu lệch nhau
     */
    AccountEntity(long customerId, ProtectedAccountNumber protectedAccountNumber) {
        if (customerId <= 0) {
            throw new IllegalArgumentException("customerId must be positive");
        }
        if (protectedAccountNumber == null) {
            throw new IllegalArgumentException("protected account number is required");
        }
        this.customerId = customerId;
        this.accountNumberEncrypted = protectedAccountNumber.encrypted();
        this.accountNumberHash = protectedAccountNumber.blindIndex();
        this.accountNumberLast4 = protectedAccountNumber.accountNumberLast4();
        this.currency = "VND";
        this.status = AccountStatus.ACTIVE;
        this.availableBalance = new BigDecimal("0.00");
        this.ledgerBalance = new BigDecimal("0.00");
    }

    Long getAccountId() {
        return accountId;
    }

    Long getCustomerId() {
        return customerId;
    }

    byte[] getAccountNumberEncrypted() {
        return accountNumberEncrypted == null ? null : accountNumberEncrypted.clone();
    }

    String getAccountNumberHash() {
        return accountNumberHash;
    }

    String getAccountNumberLast4() {
        return accountNumberLast4;
    }

    String getCurrency() {
        return currency;
    }

    AccountStatus getStatus() {
        return status;
    }

    BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    BigDecimal getLedgerBalance() {
        return ledgerBalance;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "AccountEntity[redacted]";
    }
}
