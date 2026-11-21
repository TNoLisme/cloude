package com.simulatedbank.banking.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.crypto.JcsCanonicalizer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fixed-vector tests of the hash formula, genesis and canonical timestamp/money formatting (design/01 §7). */
class AuditChainTest {

    /** Appendix B.2 canonical JSON. */
    private static final String DOCUMENTED_CANONICAL_JSON = "{\"action\":\"TRANSFER_COMPLETED\","
            + "\"actor_role\":\"CUSTOMER\",\"actor_type\":\"USER\",\"actor_user_id\":\"1001\","
            + "\"after_state\":{\"failureCode\":null,\"status\":\"COMPLETED\"},\"amount\":\"150000.00\","
            + "\"before_state\":{\"status\":\"PENDING\"},"
            + "\"client_info_masked\":{\"ipMasked\":\"203.0.113.***\",\"userAgent\":\"DigitalBanking/1.0\"},"
            + "\"correlation_id\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"currency\":\"VND\","
            + "\"entity_id\":\"TXN20261103A1B2C3D4\",\"entity_type\":\"TRANSFER\",\"hash_version\":1,"
            + "\"occurred_at\":\"2026-11-03T10:15:31.123456Z\",\"outcome\":\"SUCCESS\","
            + "\"request_id\":\"req-123\"}";

    /** Appendix B.3 previous hash. */
    private static final String DOCUMENTED_PREV_HASH =
            "9c1f5b2e7a4d8c6f0a3b5d7e9f1c2a4b6d8e0f2a4c6e8b0d2f4a6c8e0b2d4f6a";

    /** Appendix B.3 expected record hash. */
    private static final String DOCUMENTED_RECORD_HASH =
            "1c221546084975b20527994c4c1fd739741c63f7b184e2b1f1f6d203ec07405e";

    @Test
    @DisplayName("documented canonical vector hashes to the documented 64-hex record hash")
    void documentedVectorMatches() {
        assertThat(AuditChain.recordHash(DOCUMENTED_CANONICAL_JSON, DOCUMENTED_PREV_HASH))
                .isEqualTo(DOCUMENTED_RECORD_HASH)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("JCS canonicalisation reproduces the documented canonical JSON")
    void jcsReproducesDocumentedCanonicalJson() {
        String unsorted = "{ \"request_id\":\"req-123\", \"outcome\":\"SUCCESS\","
                + "\"hash_version\":1, \"occurred_at\":\"2026-11-03T10:15:31.123456Z\","
                + "\"entity_type\":\"TRANSFER\", \"entity_id\":\"TXN20261103A1B2C3D4\", \"currency\":\"VND\","
                + "\"correlation_id\":\"0f8fad5b-d9cb-469f-a165-70867728950e\","
                + "\"client_info_masked\":{\"userAgent\":\"DigitalBanking/1.0\",\"ipMasked\":\"203.0.113.***\"},"
                + "\"before_state\":{\"status\":\"PENDING\"}, \"amount\":\"150000.00\","
                + "\"after_state\":{\"status\":\"COMPLETED\",\"failureCode\":null},"
                + "\"actor_user_id\":\"1001\", \"actor_type\":\"USER\", \"actor_role\":\"CUSTOMER\","
                + "\"action\":\"TRANSFER_COMPLETED\"}";
        String canonical = new String(new JcsCanonicalizer().canonicalize(unsorted), StandardCharsets.UTF_8);
        assertThat(canonical).isEqualTo(DOCUMENTED_CANONICAL_JSON);
    }

    @Test
    @DisplayName("genesis previous hash is 64 zeros and is used for the first record")
    void genesisIsSixtyFourZeros() {
        assertThat(AuditChain.GENESIS_PREV_HASH).hasSize(64).matches("0{64}");
        assertThat(AuditChain.recordHash(DOCUMENTED_CANONICAL_JSON, AuditChain.GENESIS_PREV_HASH))
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(AuditChain.recordHash(DOCUMENTED_CANONICAL_JSON, DOCUMENTED_PREV_HASH));
    }

    @Test
    @DisplayName("a record's prev_hash is the previous record's record_hash")
    void chainLinksToPreviousHash() {
        String first = AuditChain.recordHash("{\"first\":1}", AuditChain.GENESIS_PREV_HASH);
        String second = AuditChain.recordHash("{\"second\":2}", first);
        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(second).matches("[0-9a-f]{64}");
        assertThat(AuditChain.recordHash("{\"second\":2}", first)).isEqualTo(second);
    }

    @Test
    @DisplayName("occurred_at is formatted as UTC with exactly six fractional digits and Z")
    void occurredAtFormatting() {
        assertThat(AuditChain.formatOccurredAt(Instant.parse("2026-11-03T10:15:31.123456789Z")))
                .isEqualTo("2026-11-03T10:15:31.123456Z");
        assertThat(AuditChain.formatOccurredAt(
                java.time.OffsetDateTime.parse("2026-11-03T17:15:31.123456+07:00").toInstant()))
                .isEqualTo("2026-11-03T10:15:31.123456Z");
        assertThat(AuditChain.formatOccurredAt(Instant.parse("2026-11-03T10:15:31Z")))
                .isEqualTo("2026-11-03T10:15:31.000000Z");
    }

    @Test
    @DisplayName("money is canonicalised to exactly two decimals and never rounded")
    void amountFormatting() {
        assertThat(AuditChain.formatAmount(new BigDecimal("150000"))).isEqualTo("150000.00");
        assertThat(AuditChain.formatAmount(new BigDecimal("150000.000"))).isEqualTo("150000.00");
        assertThatThrownBy(() -> AuditChain.formatAmount(new BigDecimal("1.234")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> AuditChain.formatAmount(new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
