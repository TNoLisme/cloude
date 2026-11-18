package com.simulatedbank.banking.limit.adapter.in;

import com.simulatedbank.banking.limit.application.LimitCustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/limits")
@RequiredArgsConstructor
public class LimitCustomerController {
    private final LimitCustomerService service;
    private final LimitViewMapper mapper;

    @GetMapping
    public LimitsResponse getLimits(Authentication authentication,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String customerId) {
        return mapper.toResponse(service.getLimits(authentication, accountId, customerId));
    }
}
