package com.bank.simulator.risk.web;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.risk.application.RiskFlagPage;
import com.bank.simulator.risk.application.RiskFlagQuery;
import com.bank.simulator.risk.application.RiskEvaluationService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
public class RiskFlagController {
    private final RiskEvaluationService risk;

    public RiskFlagController(RiskEvaluationService risk) {
        this.risk = risk;
    }

    @GetMapping("/operator/risk-flags")
    public RiskFlagPage list(@RequestParam(defaultValue = "20") int limit,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(required = false) String ruleId,
                             @RequestParam(required = false) UUID transferId,
                             @RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to) {
        var actor = (AuthenticatedActor) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return risk.list(new RiskFlagQuery(limit, cursor, ruleId, transferId, from, to), actor);
    }
}
