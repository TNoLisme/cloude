package com.simulatedbank.banking.limit.application.boundaryfixture;

import com.simulatedbank.banking.limit.adapter.out.LimitJpaAdapter;

/** Deliberately invalid dependency used only to verify the architecture rule. */
public class InvalidApplicationDependency {
    LimitJpaAdapter adapter;
}
