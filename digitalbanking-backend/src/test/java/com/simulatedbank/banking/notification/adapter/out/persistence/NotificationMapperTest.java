package com.simulatedbank.banking.notification.adapter.out.persistence;

import com.simulatedbank.banking.notification.domain.NotificationChannel;
import com.simulatedbank.banking.notification.domain.NotificationItem;
import com.simulatedbank.banking.notification.domain.NotificationStatus;
import com.simulatedbank.banking.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMapperTest {

    private final NotificationMapper mapper = new NotificationMapperImpl();

    @Test
    @DisplayName("MapStruct maps between NotificationEntity and NotificationItem bidirectionally")
    void bidirectionalMapping() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");
        Map<String, Object> payload = Map.of("amount", "100000.00", "currency", "VND");

        NotificationItem domain = new NotificationItem(
                1L,
                100L,
                200L,
                300L,
                NotificationType.TRANSFER_RESULT,
                NotificationChannel.IN_APP,
                "TPL_TRANSFER",
                payload,
                NotificationStatus.PENDING,
                0,
                now.plusSeconds(30),
                null,
                null,
                "CORR-123",
                null,
                null,
                0L,
                now,
                now
        );

        NotificationEntity entity = mapper.toEntity(domain);
        assertThat(entity).isNotNull();
        assertThat(entity.getNotificationId()).isEqualTo(1L);
        assertThat(entity.getCustomerId()).isEqualTo(100L);
        assertThat(entity.getTransferId()).isEqualTo(200L);
        assertThat(entity.getTemplateCode()).isEqualTo("TPL_TRANSFER");
        assertThat(entity.getPayloadMasked()).isEqualTo(payload);
        assertThat(entity.getStatus()).isEqualTo(NotificationStatus.PENDING);

        NotificationItem mappedBack = mapper.toDomain(entity);
        assertThat(mappedBack).isEqualTo(domain);
    }
}
