package com.bank.simulator.identity.web;

import com.bank.simulator.identity.config.OtpMailboxProperties;
import com.bank.simulator.identity.infrastructure.otp.LocalMailboxOtpSender;
import com.bank.simulator.identity.infrastructure.otp.OtpMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalOtpMailboxControllerTest {

    @Test
    void returnsOtpOnlyWithLocalGuard() {
        LocalMailboxOtpSender sender = new LocalMailboxOtpSender(Clock.fixed(
                Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC));
        sender.send(new OtpMessage(UUID.randomUUID(), "0912345678", "000042",
                Instant.parse("2026-10-02T00:02:00Z")));
        LocalOtpMailboxController controller = new LocalOtpMailboxController(sender, new OtpMailboxProperties(true, "local-secret"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Local-Mailbox-Token", "local-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = controller.getOtp("0912345678", request, response);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().code()).isEqualTo("000042");
    }

    @Test
    void rejectsMissingLocalGuard() {
        LocalMailboxOtpSender sender = new LocalMailboxOtpSender(Clock.systemUTC());
        LocalOtpMailboxController controller = new LocalOtpMailboxController(sender, new OtpMailboxProperties(true, "local-secret"));

        var result = controller.getOtp("0912345678", new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(result.getStatusCode().value()).isEqualTo(404);
    }
}
