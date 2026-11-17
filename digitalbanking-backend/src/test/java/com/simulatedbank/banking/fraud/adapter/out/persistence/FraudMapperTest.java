package com.simulatedbank.banking.fraud.adapter.out.persistence;

import com.simulatedbank.banking.fraud.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FraudMapperTest {

    private final FraudRuleConfigMapper ruleMapper = new FraudRuleConfigMapperImpl();
    private final FraudFlagMapper flagMapper = new FraudFlagMapperImpl();

    @Test
    @DisplayName("MapStruct maps between FraudRuleConfigEntity and FraudRuleConfig bidirectionally")
    void ruleConfigBidirectionalMapping() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");

        FraudRuleConfig domain = new FraudRuleConfig(
                "LARGE_VALUE",
                1,
                "Large value transfer rule",
                RuleType.LARGE_VALUE,
                new BigDecimal("50000000.00"),
                null,
                null,
                Severity.HIGH,
                true,
                now,
                null,
                10L,
                20L,
                now,
                now
        );

        FraudRuleConfigEntity entity = ruleMapper.toEntity(domain);
        assertThat(entity).isNotNull();
        assertThat(entity.getRuleCode()).isEqualTo("LARGE_VALUE");
        assertThat(entity.getVersion()).isEqualTo(1);
        assertThat(entity.getRuleType()).isEqualTo(RuleType.LARGE_VALUE);
        assertThat(entity.getThresholdAmount()).isEqualByComparingTo("50000000.00");

        FraudRuleConfig mappedBack = ruleMapper.toDomain(entity);
        assertThat(mappedBack).isEqualTo(domain);
    }

    @Test
    @DisplayName("MapStruct maps between FraudFlagEntity and FraudFlagItem bidirectionally")
    void fraudFlagBidirectionalMapping() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");
        Map<String, Object> matchedValue = Map.of("amount", "60000000.00", "threshold", "50000000.00");

        FraudFlagItem domain = new FraudFlagItem(
                1L,
                1001L,
                "LARGE_VALUE",
                1,
                Severity.HIGH,
                FlagStatus.OPEN,
                matchedValue,
                null,
                null,
                null,
                null,
                "CORR-456",
                now
        );

        FraudFlagEntity entity = flagMapper.toEntity(domain);
        assertThat(entity).isNotNull();
        assertThat(entity.getFlagId()).isEqualTo(1L);
        assertThat(entity.getTransferId()).isEqualTo(1001L);
        assertThat(entity.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(entity.getMatchedValue()).isEqualTo(matchedValue);

        FraudFlagItem mappedBack = flagMapper.toDomain(entity);
        assertThat(mappedBack).isEqualTo(domain);
    }
}
