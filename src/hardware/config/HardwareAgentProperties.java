package com.endpointposture.hardware.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.hardware")
public class HardwareAgentProperties {
    private String scriptPath;
    private String serverBaseUrl;
    private int cimTimeoutSeconds = 30;
    private int submitTimeoutSeconds = 20;
    private int processTimeoutSeconds = 70;

    public String getScriptPath() { return scriptPath; }
    public void setScriptPath(String v) { this.scriptPath = v; }
    public String getServerBaseUrl() { return serverBaseUrl; }
    public void setServerBaseUrl(String v) { this.serverBaseUrl = v; }
    public int getCimTimeoutSeconds() { return cimTimeoutSeconds; }
    public void setCimTimeoutSeconds(int v) { this.cimTimeoutSeconds = v; }
    public int getSubmitTimeoutSeconds() { return submitTimeoutSeconds; }
    public void setSubmitTimeoutSeconds(int v) { this.submitTimeoutSeconds = v; }
    public int getProcessTimeoutSeconds() { return processTimeoutSeconds; }
    public void setProcessTimeoutSeconds(int v) { this.processTimeoutSeconds = v; }
}