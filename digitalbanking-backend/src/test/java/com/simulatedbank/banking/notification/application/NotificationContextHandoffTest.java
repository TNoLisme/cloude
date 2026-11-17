package com.simulatedbank.banking.notification.application;

import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationContextHandoffTest {
    @Test
    void explicitHandoffBindsAndClearsContextOnWorkerThread() throws Exception {
        var context = new RequestContext("flow:notification", UUID.randomUUID().toString(), null, null);
        try (var executor = Executors.newSingleThreadExecutor(); var caller = RequestContextHolder.bind(context)) {
            var captured = RequestContextHolder.current().orElseThrow();
            executor.submit(() -> {
                assertThat(RequestContextHolder.current()).isEmpty();
                try (var worker = RequestContextHolder.bind(captured)) {
                    assertThat(RequestContextHolder.current()).contains(captured);
                }
                assertThat(RequestContextHolder.current()).isEmpty();
            }).get();
            assertThat(RequestContextHolder.current()).contains(context);
        }
        assertThat(RequestContextHolder.current()).isEmpty();
    }
}
