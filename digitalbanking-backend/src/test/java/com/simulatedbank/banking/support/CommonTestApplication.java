package com.simulatedbank.banking.support;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/** Common-only harness for tests which deliberately disable persistence. */
@Configuration(proxyBeanMethods = false)
@TestComponent
@EnableAutoConfiguration
@ConfigurationPropertiesScan("com.simulatedbank.banking.common")
@ComponentScan(basePackages = "com.simulatedbank.banking.common", excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class CommonTestApplication {
}
