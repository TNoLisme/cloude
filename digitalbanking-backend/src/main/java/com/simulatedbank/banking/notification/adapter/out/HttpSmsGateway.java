package com.simulatedbank.banking.notification.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.customer.api.CustomerDeliveryContact;
import com.simulatedbank.banking.notification.api.OtpGateway;
import com.simulatedbank.banking.notification.application.port.NotificationDeliveryPort;
import com.simulatedbank.banking.notification.domain.NotificationChannel;
import com.simulatedbank.banking.notification.domain.NotificationItem;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/** Calls the configured SMS simulator synchronously. The simulator's response body is discarded. */
public final class HttpSmsGateway implements OtpGateway, NotificationDeliveryPort {
    private static final Duration DEADLINE = Duration.ofSeconds(5);

    private final URI endpoint;
    private final HttpClient client;
    private final ObjectMapper json;
    private final CustomerDeliveryContact contacts;
    private final Map<Long, String> demoStaffContacts;

    public HttpSmsGateway(URI endpoint, ObjectMapper json, CustomerDeliveryContact contacts,
            Map<Long, String> demoStaffContacts) {
        if (!("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid SMS endpoint");
        }
        this.endpoint = endpoint;
        this.client = HttpClient.newBuilder().connectTimeout(DEADLINE).build();
        this.json = json;
        this.contacts = contacts;
        if (demoStaffContacts.entrySet().stream().anyMatch(entry ->
                entry.getKey() <= 0 || !entry.getValue().matches("\\+?[0-9]{8,15}"))) {
            throw new IllegalArgumentException("Invalid demo staff contact configuration");
        }
        this.demoStaffContacts = Map.copyOf(demoStaffContacts);
    }

    @Override
    public Result sendOtp(String ref, long userId, String channel, char[] otp, Instant expiresAt, String correlation) {
        if (!"SMS".equals(channel)) {
            return new Result(Outcome.FAILED, Failure.REJECTED);
        }
        var contact = contacts.forOtpUser(userId);
        String destination = contact instanceof CustomerDeliveryContact.Available available ? available.phone()
                : contact == CustomerDeliveryContact.Unavailable.NOT_FOUND ? demoStaffContacts.get(userId) : null;
        if (destination == null) {
            return new Result(Outcome.FAILED, Failure.REJECTED);
        }
        try {
            int status = send("otp:" + ref + ":" + expiresAt,
                    Map.of("type", "OTP", "destination", destination, "otp", otp,
                            "expiresAt", expiresAt.toString(), "correlationId", correlation));
            if (accepted(status)) {
                return new Result(Outcome.ACCEPTED, Failure.NONE);
            }
            if (status == 429) {
                return new Result(Outcome.RETRYABLE, Failure.THROTTLED);
            }
            return status >= 500 ? new Result(Outcome.RETRYABLE, Failure.UNAVAILABLE)
                    : new Result(Outcome.FAILED, Failure.REJECTED);
        } catch (HttpTimeoutException failure) {
            return new Result(Outcome.RETRYABLE, Failure.TIMEOUT);
        } catch (IOException failure) {
            return new Result(Outcome.RETRYABLE, Failure.AMBIGUOUS);
        }
    }

    @Override
    public void send(NotificationItem item) {
        if (item.channel() != NotificationChannel.SMS
                || !(contacts.forTransferOutcome(item.customerId()) instanceof CustomerDeliveryContact.Available contact)) {
            throw new PermanentFailure("DESTINATION_UNAVAILABLE");
        }
        try {
            int status = send("transfer-notice:" + item.notificationId(),
                    Map.of("type", "TRANSFER_RESULT", "destination", contact.phone(), "template", item.templateCode(),
                            "payload", item.payloadMasked(), "correlationId", item.correlationId()));
            if (!accepted(status)) {
                if (status >= 400 && status < 500 && status != 408 && status != 429) {
                    throw new PermanentFailure("PROVIDER_REJECTED");
                }
                throw new IllegalStateException("Provider unavailable");
            }
        } catch (HttpTimeoutException failure) {
            throw new IllegalStateException("Gateway timeout");
        } catch (IOException failure) {
            throw new IllegalStateException("Provider unavailable");
        }
    }

    private int send(String key, Object fields) throws IOException {
        byte[] body = json.writeValueAsBytes(fields);
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(DEADLINE)
                    .header("Content-Type", "application/json").header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var ignored = response.body()) {
                return response.statusCode();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("SMS dispatch interrupted");
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static boolean accepted(int status) {
        return status >= 200 && status < 300;
    }
}
