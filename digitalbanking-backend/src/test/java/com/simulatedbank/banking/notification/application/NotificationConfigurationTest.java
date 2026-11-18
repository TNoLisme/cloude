package com.simulatedbank.banking.notification.application;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.notification.adapter.out.SimulatedNotificationGateway;
import com.simulatedbank.banking.notification.application.port.NotificationDeliveryPort;
import com.simulatedbank.banking.notification.application.port.NotificationPersistencePort;
import com.simulatedbank.banking.notification.configuration.NotificationConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationConfiguration.class)
            .withBean(NotificationPersistencePort.class, () -> Mockito.mock(NotificationPersistencePort.class))
            .withBean(TransactionTemplate.class, () -> Mockito.mock(TransactionTemplate.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(AppProperties.class, () -> Mockito.mock(AppProperties.class));

    @Test
    @DisplayName("1. In default profile without explicit opt-in, simulator gateway and worker are NOT registered (fail-closed)")
    void defaultProfileWithoutOptInDoesNotRegisterSimulatorNorWorker() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SimulatedNotificationGateway.class);
            assertThat(context).doesNotHaveBean(NotificationOutboxWorker.class);
        });
    }

    @Test
    @DisplayName("2. In explicit prod profile without real gateway, simulator and worker are NOT registered")
    void explicitProdProfileWithoutRealGatewayDoesNotActivateWorker() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).doesNotHaveBean(SimulatedNotificationGateway.class);
            assertThat(context).doesNotHaveBean(NotificationOutboxWorker.class);
        });
    }

    @Test
    @DisplayName("3. Explicit simulator profile opt-in registers simulator gateway and activates worker with isSimulator=true")
    void explicitSimulatorProfileOptInRegistersSimulatorAndActivatesWorker() {
        runner.withPropertyValues("spring.profiles.active=simulator").run(context -> {
            assertThat(context).hasSingleBean(SimulatedNotificationGateway.class);
            assertThat(context).hasSingleBean(NotificationOutboxWorker.class);
            SimulatedNotificationGateway gateway = context.getBean(SimulatedNotificationGateway.class);
            assertThat(gateway.isSimulator()).isTrue();
        });
    }

    @Test
    @DisplayName("4. Explicit property opt-in registers simulator gateway and activates worker with isSimulator=true")
    void explicitPropertyOptInRegistersSimulatorAndActivatesWorker() {
        runner.withPropertyValues("app.notification.simulator.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(SimulatedNotificationGateway.class);
            assertThat(context).hasSingleBean(NotificationOutboxWorker.class);
            SimulatedNotificationGateway gateway = context.getBean(SimulatedNotificationGateway.class);
            assertThat(gateway.isSimulator()).isTrue();
        });
    }

    @Test
    @DisplayName("A direct delivery port provider creates a worker without a simulator")
    void directProviderRegistersWorker() {
        assertDirectProviderSelected("spring.profiles.active=prod");
    }

    @Test
    @DisplayName("An existing delivery port wins over the simulator profile")
    void directProviderWinsOverSimulatorProfile() {
        assertDirectProviderSelected("spring.profiles.active=simulator");
    }

    @Test
    @DisplayName("An existing delivery port wins over the simulator property")
    void directProviderWinsOverSimulatorProperty() {
        assertDirectProviderSelected("app.notification.simulator.enabled=true");
    }

    private void assertDirectProviderSelected(String property) {
        NotificationDeliveryPort provider = item -> { };
        runner.withPropertyValues(property)
                .withBean(NotificationDeliveryPort.class, () -> provider)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NotificationDeliveryPort.class);
                    assertThat(context.getBean(NotificationDeliveryPort.class)).isSameAs(provider);
                    assertThat(context).doesNotHaveBean(SimulatedNotificationGateway.class);
                    assertThat(context).hasSingleBean(NotificationOutboxWorker.class);
                });
    }
}
