package com.endpointposture.posture.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for running the posture agent, bound from {@code app.posture.*}
 * in {@code application.yml}. Nothing about the script is hardcoded in Java.
 *
 * <p>The timeouts are layered on purpose: the agent's own CIM and server
 * timeouts are short, and {@link #processTimeoutSeconds} is the outer
 * backstop that must be longer than them, so the agent can report a real
 * error before it gets killed.</p>
 */
@ConfigurationProperties(prefix = "app.posture")
public class PostureAgentProperties {

    private String scriptPath;
    private String serverUrl;
    private String apiKey;
    private int cimTimeoutSeconds = 30;
    private int serverTimeoutSeconds = 15;
    private int processTimeoutSeconds = 60; // must exceed cim+server timeouts with headroom

    /** @return path to {@code posture_agent.ps1}; relative paths resolve against the directory the app is started from */
    public String getScriptPath() { return scriptPath; }
    public void setScriptPath(String v) { this.scriptPath = v; }

    /** @return full URL the agent posts its report to, for example {@code http://localhost:8090/api/v1/posture} */
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String v) { this.serverUrl = v; }

    /**
     * @return shared secret the agent sends in the {@code X-Posture-Api-Key}
     *         header; passed to the agent through the {@code POSTURE_API_KEY}
     *         environment variable
     */
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }

    /** @return per-operation timeout for CIM/WinRM calls made by the agent, in seconds */
    public int getCimTimeoutSeconds() { return cimTimeoutSeconds; }
    public void setCimTimeoutSeconds(int v) { this.cimTimeoutSeconds = v; }

    /** @return timeout for the agent's HTTP POST back to this backend, in seconds */
    public int getServerTimeoutSeconds() { return serverTimeoutSeconds; }
    public void setServerTimeoutSeconds(int v) { this.serverTimeoutSeconds = v; }

    /** @return outer limit for the whole agent process, in seconds; it is killed if it runs longer */
    public int getProcessTimeoutSeconds() { return processTimeoutSeconds; }
    public void setProcessTimeoutSeconds(int v) { this.processTimeoutSeconds = v; }
}
