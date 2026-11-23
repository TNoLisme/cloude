package com.simulatedbank.banking.notification.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.customer.api.CustomerDeliveryContact;
import com.simulatedbank.banking.notification.adapter.out.HttpSmsGateway;
import com.simulatedbank.banking.notification.adapter.out.SimulatedNotificationGateway;
import com.simulatedbank.banking.notification.application.NotificationOutboxMetrics;
import com.simulatedbank.banking.notification.application.NotificationOutboxWorker;
import com.simulatedbank.banking.notification.application.port.NotificationDeliveryPort;
import com.simulatedbank.banking.notification.application.port.NotificationPersistencePort;
import com.simulatedbank.banking.notification.domain.NotificationPayloadValidator;
import com.simulatedbank.banking.notification.domain.NotificationRetryPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Composition configuration for notification module.
 * Wires domain services, outbound delivery simulators, and application worker.
 * Placed in module-level configuration package outside application to enforce
 * strict Hexagonal dependency direction (application layer must not depend on concrete outbound adapters).
 */
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
    @ConditionalOnProperty(name = "app.notification.sms.enabled", havingValue = "true")
    public HttpSmsGateway configuredSmsGateway(@Value("${app.notification.sms.endpoint}") URI endpoint,
            ObjectMapper json, CustomerDeliveryContact contacts, Environment environment) {
        var directory = Binder.get(environment)
                .bind("app.notification.sms.demo-staff-contacts", Bindable.mapOf(Long.class, String.class))
                .orElse(Map.of());
        if (!directory.isEmpty() && !environment.acceptsProfiles(Profiles.of("demo", "local", "test"))) {
            throw new IllegalStateException("Demo staff SMS directory requires a demo, local, or test profile");
        }
        return new HttpSmsGateway(endpoint, json, contacts, directory);
    }

    @Bean
    @ConditionalOnMissingBean(NotificationDeliveryPort.class)
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
    @ConditionalOnBean(NotificationDeliveryPort.class)
    public NotificationOutboxWorker notificationOutboxWorker(
            NotificationPersistencePort repository,
            NotificationDeliveryPort gateway,
            NotificationRetryPolicy retryPolicy,
            TransactionTemplate transactionTemplate,
            Clock clock,
            ObjectProvider<MeterRegistry> metrics
    ) {
        return new NotificationOutboxWorker(repository, gateway, retryPolicy, transactionTemplate, clock, new NotificationOutboxMetrics(
                metrics.getIfUnique(() -> Metrics.globalRegistry)));
    }

    @Bean
    @ConditionalOnBean(NotificationOutboxWorker.class)
    @ConditionalOnProperty(name = {"app.notification.worker.enabled", "app.notification.sms.enabled"}, havingValue = "true")
    public OutboxPoller notificationPoller(NotificationOutboxWorker worker, AppProperties properties) {
        return new OutboxPoller(worker, properties.jobs().outboxBatchSize());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = {"app.notification.worker.enabled", "app.notification.sms.enabled"}, havingValue = "true")
    static class Scheduling {
    }

    record OutboxPoller(NotificationOutboxWorker worker, int batchSize) {
        @Scheduled(fixedDelayString = "${app.jobs.outbox-poll-interval-ms:2000}")
        public void poll() {
            // One active lease per send. A slow provider cannot expire a batch waiting in memory.
            for (int i = 0; i < batchSize && worker.processBatch(1) != 0; i++) {
                // Stop the poll as soon as the queue has no due items.
            }
        }
    }

    public static class SimulatorOptInCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            if (env == null) {
                return false;
            }
            if (Boolean.parseBoolean(env.getProperty("app.notification.sms.enabled"))) {
                return false;
            }
            boolean profileOptIn = env.acceptsProfiles(Profiles.of("simulator"));
            boolean propertyOptIn = "true".equalsIgnoreCase(env.getProperty("app.notification.simulator.enabled"));
            return profileOptIn || propertyOptIn;
        }
    }
}
