package com.bank.simulator.risk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.risk")
public class RiskProperties {
    private String largeTransferThreshold = "5000000";
    private Duration frequencyWindow = Duration.ofMinutes(10);
    private int frequencyCountThreshold = 5;

    public String getLargeTransferThreshold() { return largeTransferThreshold; }
    public void setLargeTransferThreshold(String value) { this.largeTransferThreshold = value; }
    public Duration getFrequencyWindow() { return frequencyWindow; }
    public void setFrequencyWindow(Duration value) { this.frequencyWindow = value; }
    public int getFrequencyCountThreshold() { return frequencyCountThreshold; }
    public void setFrequencyCountThreshold(int value) { this.frequencyCountThreshold = value; }
}
