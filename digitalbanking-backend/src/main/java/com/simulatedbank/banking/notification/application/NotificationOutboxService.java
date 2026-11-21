package com.simulatedbank.banking.notification.application;

import com.simulatedbank.banking.notification.api.NotificationOutboxPort;
import com.simulatedbank.banking.notification.application.port.NotificationPersistencePort;
import com.simulatedbank.banking.notification.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Enqueues immutable transfer notification notices inside the caller's T2 transaction.
 */
@Service
@ConditionalOnProperty(name = "app.notification.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationOutboxService implements NotificationOutboxPort {

    private final NotificationPersistencePort repository;
    private final NotificationPayloadValidator validator;

    public NotificationOutboxService(NotificationPersistencePort repository, NotificationPayloadValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTransferSmsInT2(List<TransferSms> notices) {
        enqueueTransferNotificationsInT2(notices.stream().map(notice -> new TransferNotice(
                notice.customerId(), notice.transferId(), notice.reference(), NotificationChannel.SMS,
                "TRANSFER_RESULT", Map.of("reference", notice.reference(), "amount", notice.amount(),
                "currency", notice.currency(), "maskedAccount", notice.maskedDestination(), "outcome",
                notice.completed() ? "COMPLETED" : "FAILED"), notice.correlationId())).toList());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTransferNotificationsInT2(List<TransferNotice> notices) {
        if (notices == null || notices.isEmpty()) {
            return;
        }

        for (TransferNotice notice : notices) {
            validator.validateTransferNotice(notice);

            NotificationItem item = new NotificationItem(
                    null,
                    notice.customerId(),
                    notice.transferId(),
                    null,
                    NotificationType.TRANSFER_RESULT,
                    notice.channel(),
                    notice.templateCode(),
                    notice.payloadMasked(),
                    NotificationStatus.PENDING,
                    0,
                    Instant.now(),
                    null,
                    null,
                    notice.correlationId(),
                    null,
                    null,
                    0L,
                    null,
                    null
            );

            repository.insert(item);
        }
    }
}
