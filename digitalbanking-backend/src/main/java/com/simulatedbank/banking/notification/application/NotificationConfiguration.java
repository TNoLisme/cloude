package com.simulatedbank.banking.notification.application;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.notification.adapter.out.NotificationJdbcRepository;
import com.simulatedbank.banking.notification.adapter.out.SimulatedNotificationGateway;
import com.simulatedbank.banking.notification.domain.NotificationPayloadValidator;
import com.simulatedbank.banking.notification.domain.NotificationRetryPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class NotificationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NotificationPayloadValidator notificationPayloadValidator() {
        return new NotificationPayloadValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public NotificationRetryPolicy notificationRetryPolicy(AppProperties properties) {
        long leaseSeconds = properties.jobs() != null ? properties.jobs().outboxLeaseSeconds() : 30;
        return new NotificationRetryPolicy(5, Duration.ofSeconds(5), Duration.ofSeconds(leaseSeconds));
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(SimulatorOptInCondition.class)
    public SimulatedNotificationGateway defaultSimulatedNotificationGateway() {
        return new SimulatedNotificationGateway() {
            @Override
            public void send(com.simulatedbank.banking.notification.domain.NotificationItem item) {
                // Explicit opt-in simulator in development/testing
            }

            @Override
            public boolean isSimulator() {
                return true;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SimulatedNotificationGateway.class)
    public NotificationOutboxWorker notificationOutboxWorker(
            NotificationJdbcRepository repository,
            SimulatedNotificationGateway gateway,
            NotificationRetryPolicy retryPolicy,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        return new NotificationOutboxWorker(repository, gateway, retryPolicy, transactionTemplate, clock);
    }

    public static class SimulatorOptInCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            if (env == null) {
                return false;
            }
            boolean profileOptIn = env.acceptsProfiles(Profiles.of("simulator"));
            boolean propertyOptIn = "true".equalsIgnoreCase(env.getProperty("app.notification.simulator.enabled"));
            return profileOptIn || propertyOptIn;
        }
    }
}
