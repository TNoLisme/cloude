package com.simulatedbank.banking.ledger.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only mapping of banking.ledger_entry (BE-LED-02 §5). entry_id is database IDENTITY;
 * transfer_id/account_id are scalar IDs (no cross-module JPA relation); created_at uses the DB
 * default now(). No update/delete path is exposed; the adapter only inserts and reads.
 */
@Entity
@Table(name = "ledger_entry", schema = "banking")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "entry_seq", nullable = false)
    private short entrySeq;

    @Column(name = "entry_type", nullable = false, length = 8)
    private String entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}