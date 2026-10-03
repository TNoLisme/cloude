package com.bank.simulator.audit.application;

import java.util.List;

public record AuditPage(List<AuditEventView> items, String nextCursor) {
    public AuditPage {
        items = List.copyOf(items);
    }
}
