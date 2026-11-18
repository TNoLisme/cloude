package com.simulatedbank.banking.transfer.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.core.io.ClassPathResource;

/** Selects only the production schema initializer, since provider migrations are replaced by the Transfer fixture. */
final class TransferCandidateFlyway {
    private TransferCandidateFlyway() { }

    static FluentConfiguration locations(FluentConfiguration configuration, Path schemaDirectory) {
        Path initializer = schemaDirectory.resolve("V1__init_schema.sql");
        if (Files.notExists(initializer)) {
            try (var script = new ClassPathResource("db/migration/V1__init_schema.sql").getInputStream()) {
                Files.copy(script, initializer);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        return configuration.locations("filesystem:" + schemaDirectory,
                "classpath:db/transfer-fixture", "classpath:db/transfer-candidate");
    }
}
