package com.simulatedbank.banking.migration;

import static org.assertj.core.api.Assertions.assertThat;
import com.simulatedbank.banking.account.application.AccountService;
import com.simulatedbank.banking.admin.application.ApprovalAuditSink;
import com.simulatedbank.banking.admin.application.ApprovalService;
import com.simulatedbank.banking.customer.application.CustomerService;
import com.simulatedbank.banking.identity.api.OtpDeliveryPort;
import com.simulatedbank.banking.identity.application.IdentityService;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PostgreSqlFlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking")
            .withUsername("postgres")
            .withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "banking");
        registry.add("spring.flyway.default-schema", () -> "banking");
        registry.add("banking.modules.enabled", () -> true);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void flywayAppliesCanonicalSchemaAndFullBusinessContextLoads() {
        Integer schemaExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'banking'",
                Integer.class
        );
        assertThat(schemaExists).isEqualTo(1);

        Integer v1Applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM banking.flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class
        );
        Integer v2Applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM banking.flyway_schema_history WHERE version = '2' AND success = true",
                Integer.class
        );
        assertThat(v1Applied).isEqualTo(1);
        assertThat(v2Applied).isEqualTo(1);

        Integer probeExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'banking' AND table_name = 'foundation_probe'",
                Integer.class
        );
        assertThat(probeExists).isZero();

        String currentSchema = (String) entityManager
                .createNativeQuery("SELECT current_schema()")
                .getSingleResult();
        assertThat(currentSchema).isEqualTo("banking");

        assertThat(applicationContext.getBeansOfType(IdentityService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(CustomerService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AccountService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ApprovalService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(OtpDeliveryPort.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ApprovalAuditSink.class)).isEmpty();
    }
}
