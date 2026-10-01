package com.endpointposture.diagnostic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the diagnostic agent, bound from {@code app.diagnostics.*}.
 *
 * <p>Layered timeouts, as with the other agents: WinRM open (20) + WinRM
 * operation (70) + submit (20) = 110 s must stay below
 * {@link #processTimeoutSeconds} (130), so the agent can report a real error
 * before the worker kills it.</p>
 */
@ConfigurationProperties(prefix = "app.diagnostics")
public class DiagnosticAgentProperties {

    private String scriptPath;
    private String serverBaseUrl;
    private int winrmOpenTimeoutSeconds = 20;
    private int winrmOperationTimeoutSeconds = 70;
    private int submitTimeoutSeconds = 20;
    private int processTimeoutSeconds = 130;
    private String dnsTestName = "www.microsoft.com";
    private String internetTarget = "8.8.8.8";

    /** @return path to {@code diagnostic_agent.ps1}; relative paths resolve against the directory the app is started from */
    public String getScriptPath() { return scriptPath; }
    public void setScriptPath(String v) { this.scriptPath = v; }

    /** @return base URL of this backend (no path); the agent appends its own route */
    public String getServerBaseUrl() { return serverBaseUrl; }
    public void setServerBaseUrl(String v) { this.serverBaseUrl = v; }

    /** @return how long the agent waits to open the WinRM session, in seconds */
    public int getWinrmOpenTimeoutSeconds() { return winrmOpenTimeoutSeconds; }
    public void setWinrmOpenTimeoutSeconds(int v) { this.winrmOpenTimeoutSeconds = v; }

    /** @return how long the probes may run on the endpoint, in seconds */
    public int getWinrmOperationTimeoutSeconds() { return winrmOperationTimeoutSeconds; }
    public void setWinrmOperationTimeoutSeconds(int v) { this.winrmOperationTimeoutSeconds = v; }

    /** @return timeout for the agent's HTTP POST back to this backend, in seconds */
    public int getSubmitTimeoutSeconds() { return submitTimeoutSeconds; }
    public void setSubmitTimeoutSeconds(int v) { this.submitTimeoutSeconds = v; }

    /** @return outer limit for the whole agent process, in seconds; it is killed if it runs longer */
    public int getProcessTimeoutSeconds() { return processTimeoutSeconds; }
    public void setProcessTimeoutSeconds(int v) { this.processTimeoutSeconds = v; }

    /** @return host name used for the DNS lookup and the TCP 443 probe */
    public String getDnsTestName() { return dnsTestName; }
    public void setDnsTestName(String v) { this.dnsTestName = v; }

    /** @return address used for the internet ping and the traceroute */
    public String getInternetTarget() { return internetTarget; }
    public void setInternetTarget(String v) { this.internetTarget = v; }
}