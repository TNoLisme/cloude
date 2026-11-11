package com.simulatedbank.banking;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.common.config.AppProperties;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@ActiveProfiles("test")
class BackendApplicationContextTest {

    @Autowired
    private Clock clock;

    @Autowired
    private ZoneId businessZoneId;

    @Autowired
    private AppProperties appProperties;

    @Test
    void contextLoadsWithFoundationBeans() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(businessZoneId).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(appProperties.security().cookie().name()).isEqualTo("DB_SESSION");
    }
}
