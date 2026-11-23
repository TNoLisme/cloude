package com.simulatedbank.banking.common.config;

import org.springframework.security.core.Authentication;

/** Technical extension point for reporting authorization denials without depending on business modules. */
public interface SecurityDenialReporter {
    void report(Authentication authentication, String path, String reason);
}
