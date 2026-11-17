package com.simulatedbank.banking.fraud.application;

import com.simulatedbank.banking.fraud.domain.FraudRuleEvaluator;
import com.simulatedbank.banking.fraud.domain.ReviewStateTransition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FraudConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReviewStateTransition reviewStateTransition() {
        return new ReviewStateTransition();
    }

    @Bean
    @ConditionalOnMissingBean
    public FraudRuleEvaluator fraudRuleEvaluator() {
        return new FraudRuleEvaluator();
    }
}
