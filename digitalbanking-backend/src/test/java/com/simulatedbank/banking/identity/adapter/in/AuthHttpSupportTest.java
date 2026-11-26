package com.simulatedbank.banking.identity.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthHttpSupportTest {
    @Test
    void publicV1PrivilegesExposesOnlyChecker() {
        assertThat(AuthHttpSupport.publicV1Privileges(Set.of(
                "ACCOUNT_READ_SELF", "LIMIT_READ_SELF", "TRANSFER_CREATE")))
                .isEmpty();
        assertThat(AuthHttpSupport.publicV1Privileges(Set.of(
                "ROLE_ADMIN", "ROLE_MANAGE", "CHECKER", "ACCOUNT_READ")))
                .isEqualTo(List.of("CHECKER"));
    }
}
