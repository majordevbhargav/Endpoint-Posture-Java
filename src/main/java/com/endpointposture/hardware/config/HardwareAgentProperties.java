package com.endpointposture.hardware.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for running the hardware-health agent, bound from
 * {@code app.hardware.*} in {@code application.yml}.
 *
 * <p>Same layered-timeout idea as
 * {@link com.endpointposture.posture.config.PostureAgentProperties}: short
 * inner timeouts, with {@link #processTimeoutSeconds} as the outer backstop.</p>
 */
@ConfigurationProperties(prefix = "app.hardware")
public class HardwareAgentProperties {

    private String scriptPath;
    private String serverBaseUrl;
    private int cimTimeoutSeconds = 30;
    private int submitTimeoutSeconds = 20;
    private int processTimeoutSeconds = 70;

    /** @return path to {@code hardware_health_agent.ps1}; relative paths resolve against the directory the app is started from */
    public String getScriptPath() { return scriptPath; }
    public void setScriptPath(String v) { this.scriptPath = v; }

    /** @return base URL of this backend (no path); the agent appends its own route */
    public String getServerBaseUrl() { return serverBaseUrl; }
    public void setServerBaseUrl(String v) { this.serverBaseUrl = v; }

    /** @return per-operation timeout for CIM/WinRM calls made by the agent, in seconds */
    public int getCimTimeoutSeconds() { return cimTimeoutSeconds; }
    public void setCimTimeoutSeconds(int v) { this.cimTimeoutSeconds = v; }

    /** @return timeout for the agent's HTTP POST back to this backend, in seconds */
    public int getSubmitTimeoutSeconds() { return submitTimeoutSeconds; }
    public void setSubmitTimeoutSeconds(int v) { this.submitTimeoutSeconds = v; }

    /** @return outer limit for the whole agent process, in seconds; it is killed if it runs longer */
    public int getProcessTimeoutSeconds() { return processTimeoutSeconds; }
    public void setProcessTimeoutSeconds(int v) { this.processTimeoutSeconds = v; }
}
