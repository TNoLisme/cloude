package com.simulatedbank.banking.common.config;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneId businessZoneId(AppProperties appProperties) {
        try {
            return ZoneId.of(appProperties.timezone());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("app.timezone must be a valid ZoneId");
        }
    }
}
