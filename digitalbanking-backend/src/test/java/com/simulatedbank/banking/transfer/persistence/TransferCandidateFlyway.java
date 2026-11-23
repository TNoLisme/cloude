package com.simulatedbank.banking.transfer.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.core.io.ClassPathResource;

/**
 * Copies the canonical provider migrations V1–V11 (Identity/Customer/Admin/Account/Limit/Audit) next to the
 * Transfer fixture seeds (V5_5/V8_5) and the candidate Transfer DDL (V12). The seeds insert into the real provider
 * tables, so those tables must exist; V12+ of the runtime chain is intentionally excluded because the candidate
 * transfer/idempotency schema owns version 12 here.
 */
final class TransferCandidateFlyway {
    private static final List<String> PROVIDER_MIGRATIONS = List.of(
            "V1__init_schema.sql",
            "V2__create_identity_and_rbac_tables.sql",
            "V3__add_fido_verification_material.sql",
            "V4__add_role_assignment_version.sql",
            "V5__create_customer_table.sql",
            "V6__create_approval_request_table.sql",
            "V7__create_admin_idempotency_record_table.sql",
            "V8__create_account_table.sql",
            "V9__create_limit_config_table.sql",
            "V10__create_audit_log_table.sql",
            "V11__create_admin_approval_attempt_table.sql");

    private TransferCandidateFlyway() { }

    static FluentConfiguration locations(FluentConfiguration configuration, Path schemaDirectory) {
        for (String name : PROVIDER_MIGRATIONS) {
            Path target = schemaDirectory.resolve(name);
            if (Files.notExists(target)) {
                try (var script = new ClassPathResource("db/migration/" + name).getInputStream()) {
                    Files.copy(script, target);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            }
        }
        return configuration.locations("filesystem:" + schemaDirectory,
                "classpath:db/transfer-fixture", "classpath:db/transfer-candidate");
    }
}