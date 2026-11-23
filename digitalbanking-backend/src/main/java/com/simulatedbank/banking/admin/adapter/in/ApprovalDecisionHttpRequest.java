package com.simulatedbank.banking.admin.adapter.in;

/** API-LIM-03/API-ROL-02 decision body (07 §8.5.3/§8.8.2). */
public record ApprovalDecisionHttpRequest(String decision, String decisionNote) {
}
