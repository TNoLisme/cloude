package com.bank.simulator.identity.web;

import com.bank.simulator.identity.config.OtpMailboxProperties;
import com.bank.simulator.identity.infrastructure.otp.LocalMailboxOtpSender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "demo"})
public class LocalOtpMailboxController {

    private final LocalMailboxOtpSender mailbox;
    private final String guardToken;

    public LocalOtpMailboxController(LocalMailboxOtpSender mailbox, OtpMailboxProperties properties) {
        this.mailbox = mailbox;
        this.guardToken = properties.mailboxGuardToken();
    }

    @GetMapping("/__local/otp-mailbox")
    public ResponseEntity<OtpMailboxResponse> getOtp(@RequestParam String identifier,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        if (guardToken.isBlank() || !guardToken.equals(request.getHeader("X-Local-Mailbox-Token"))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String code = mailbox.findCode(identifier);
        if (code == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new OtpMailboxResponse(identifier, code));
    }

    public record OtpMailboxResponse(String identifier, String code) {
    }
}
