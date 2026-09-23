package com.endpointposture.ise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ISE connection and enforcement settings, bound from {@code app.ise.*}.
 * All fields default to blank/disabled so the app boots and the session
 * watcher and ISE actions safely no-op when ISE isn't configured yet.
 *
 * <p>This replaces the earlier version of this file: it adds
 * {@link #enforcementMode} and {@link #ancPolicyName}, used by
 * {@code ErsIseTransport} to decide how {@code publishEnforcement}
 * behaves. Everything already reading {@code baseUrl}/{@code username}/
 * {@code password}/{@code verifyTls}/{@code sessionPollIntervalMs}
 * (namely {@code IseSessionClient}) is unaffected.</p>
 */
@ConfigurationProperties(prefix = "app.ise")
public class IseProperties {

    /**
     * How {@code publishEnforcement} restricts an endpoint.
     * {@code ATTRIBUTE}: writes a compliance attribute and triggers a CoA
     * re-authentication so ISE's own policy re-evaluates the session.
     * {@code ANC}: applies/clears a named Adaptive Network Control policy
     * (see {@link #ancPolicyName}) directly.
     */
    public enum Mode { ATTRIBUTE, ANC }

    /** e.g. https://10.6.1.90 — blank disables the session watcher and every ISE action. */
    private String baseUrl = "";
    private String username = "";
    private String password = "";
    private boolean verifyTls = false;
    private long sessionPollIntervalMs = 15000;
    private Mode enforcementMode = Mode.ATTRIBUTE;

    /**
     * ANC policy name applied on RESTRICT when {@link #enforcementMode} is
     * {@code ANC} and the request doesn't override it. Confirm this matches
     * the actual policy name configured in the target ISE deployment
     * (project plan Section 15, question 6) before relying on it.
     */
    private String ancPolicyName = "Quarantine";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = v; }
    public boolean isVerifyTls() { return verifyTls; }
    public void setVerifyTls(boolean v) { this.verifyTls = v; }
    public long getSessionPollIntervalMs() { return sessionPollIntervalMs; }
    public void setSessionPollIntervalMs(long v) { this.sessionPollIntervalMs = v; }
    public Mode getEnforcementMode() { return enforcementMode; }
    public void setEnforcementMode(Mode v) { this.enforcementMode = v; }
    public String getAncPolicyName() { return ancPolicyName; }
    public void setAncPolicyName(String v) { this.ancPolicyName = v; }

    /** @return whether enough is configured to actually talk to ISE */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}