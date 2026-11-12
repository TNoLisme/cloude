package com.simulatedbank.banking.common.config;

import com.simulatedbank.banking.common.context.CorrelationAndRequestIdFilter;
import com.simulatedbank.banking.common.error.ProblemWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RequestContextConfig {

    @Bean
    public FilterRegistrationBean<CorrelationAndRequestIdFilter> correlationAndRequestIdFilter(ProblemWriter writer) {
        var registration = new FilterRegistrationBean<>(new CorrelationAndRequestIdFilter(writer));
        registration.setName("correlationAndRequestIdFilter");
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
