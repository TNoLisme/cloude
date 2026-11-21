package com.simulatedbank.banking;

import com.simulatedbank.qafixture.LocalQaSeed;
import com.simulatedbank.transfercandidate.web.TransferRuntimeConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@Import({TransferRuntimeConfiguration.class, LocalQaSeed.class})
@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
