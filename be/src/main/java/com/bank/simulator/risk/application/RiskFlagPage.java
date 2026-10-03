package com.bank.simulator.risk.application;

import java.util.List;

public record RiskFlagPage(List<RiskFlagView> items, String nextCursor) {
    public RiskFlagPage {
        items = List.copyOf(items);
    }
}
