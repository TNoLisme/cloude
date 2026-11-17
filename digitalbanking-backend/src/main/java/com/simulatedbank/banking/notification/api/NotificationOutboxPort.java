package com.simulatedbank.banking.notification.api;

import com.simulatedbank.banking.notification.domain.TransferNotice;

import java.util.List;

/**
 * Port for enqueuing transfer notifications in T2 transaction.
 */
public interface NotificationOutboxPort {

    void enqueueTransferNotificationsInT2(List<TransferNotice> notices);
}
