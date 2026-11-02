package com.bank.simulator.identity.web;

import com.bank.simulator.identity.infrastructure.security.CsrfTokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {

    private final CsrfTokenService csrfTokenService;

    public CsrfController(CsrfTokenService csrfTokenService) {
        this.csrfTokenService = csrfTokenService;
    }

    @GetMapping("/auth/csrf")
    public CsrfTokenResponse getToken() {
        return new CsrfTokenResponse(csrfTokenService.issue());
    }

    public record CsrfTokenResponse(String csrfToken) {
    }
}
