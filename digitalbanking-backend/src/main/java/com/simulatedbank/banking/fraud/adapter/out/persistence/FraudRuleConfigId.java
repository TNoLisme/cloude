package com.simulatedbank.banking.fraud.adapter.out.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FraudRuleConfigId implements Serializable {
    private String ruleCode;
    private int version;
}
