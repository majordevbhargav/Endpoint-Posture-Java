package com.endpointposture.indicator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the security-indicator agent, bound from app.security-indicators.*.
 * Keep winrmOperationTimeoutSeconds above (sampleCount-1)*sampleIntervalSeconds, and
 * open + operation + submit below processTimeoutSeconds.
 */
@ConfigurationProperties(prefix = "app.security-indicators")
public class SecurityIndicatorAgentProperties {
    private String scriptPath;
    private String serverBaseUrl;
    private int winrmOpenTimeoutSeconds = 20;
    private int winrmOperationTimeoutSeconds = 100;
    private int submitTimeoutSeconds = 20;
    private int sampleCount = 12;
    private int sampleIntervalSeconds = 5;
    private int processTimeoutSeconds = 170;

    public String getScriptPath() { return scriptPath; }
    public void setScriptPath(String v) { this.scriptPath = v; }
    public String getServerBaseUrl() { return serverBaseUrl; }
    public void setServerBaseUrl(String v) { this.serverBaseUrl = v; }
    public int getWinrmOpenTimeoutSeconds() { return winrmOpenTimeoutSeconds; }
    public void setWinrmOpenTimeoutSeconds(int v) { this.winrmOpenTimeoutSeconds = v; }
    public int getWinrmOperationTimeoutSeconds() { return winrmOperationTimeoutSeconds; }
    public void setWinrmOperationTimeoutSeconds(int v) { this.winrmOperationTimeoutSeconds = v; }
    public int getSubmitTimeoutSeconds() { return submitTimeoutSeconds; }
    public void setSubmitTimeoutSeconds(int v) { this.submitTimeoutSeconds = v; }
    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int v) { this.sampleCount = v; }
    public int getSampleIntervalSeconds() { return sampleIntervalSeconds; }
    public void setSampleIntervalSeconds(int v) { this.sampleIntervalSeconds = v; }
    public int getProcessTimeoutSeconds() { return processTimeoutSeconds; }
    public void setProcessTimeoutSeconds(int v) { this.processTimeoutSeconds = v; }
}