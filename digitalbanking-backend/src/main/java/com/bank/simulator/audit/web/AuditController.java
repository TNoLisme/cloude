package com.bank.simulator.audit.web;

import com.bank.simulator.audit.application.AuditPage;
import com.bank.simulator.audit.application.AuditQuery;
import com.bank.simulator.audit.application.ListAuditEventsService;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
public class AuditController {
    private final ListAuditEventsService audit;

    public AuditController(ListAuditEventsService audit) {
        this.audit = audit;
    }

    @GetMapping("/audit-events")
    public AuditPage list(@RequestParam(defaultValue = "20") int limit,
                          @RequestParam(required = false) String cursor,
                          @RequestParam(required = false) String eventType,
                          @RequestParam(required = false) UUID actorId,
                          @RequestParam(required = false) Instant from,
                          @RequestParam(required = false) Instant to) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return audit.list(new AuditQuery(limit, cursor, eventType, actorId, from, to),
                (AuthenticatedActor) authentication.getPrincipal());
    }
}
