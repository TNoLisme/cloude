package com.simulatedbank.banking;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.common.config.AppProperties;
import com.simulatedbank.banking.common.config.TimeConfig;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = BackendApplicationContextTest.FoundationConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BackendApplicationContextTest {

    @Configuration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
    })
    @EnableConfigurationProperties(AppProperties.class)
    @Import(TimeConfig.class)
    static class FoundationConfig { }

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
