package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FlywayMigrationTest {

    @Test
    void foundationMigrationDeclaresBankingSchemaWithoutProbeTable() throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V1__init_schema.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("CREATE SCHEMA IF NOT EXISTS banking;");
            assertThat(sql).doesNotContain("foundation_probe");
        }
    }
}
