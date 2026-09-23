package com.endpointposture.ise;

/**
 * Transport-agnostic interface for talking to Cisco ISE. Exactly two
 * operations, matching everything this platform is allowed to do per its
 * enforcement model (project plan Section 11): publish a posture fact, or
 * publish an enforcement action. Neither happens automatically — every
 * call originates from {@link IseActionService}, itself only reachable
 * through an explicit operator click.
 *
 * <p>{@link ErsIseTransport} is the only implementation for now (ISE ERS
 * REST / Context-In API). A pxGrid implementation would be a second bean
 * behind this same interface, added only once pxGrid persona and client
 * certificates are actually available — not built speculatively.</p>
 */
public interface IseTransport {

    /**
     * Shares a posture fact with ISE as a custom endpoint attribute.
     * ISE's own Authorization Policy decides what to do with it — this
     * method makes no access decision itself.
     *
     * @param mac     the endpoint's MAC address
     * @param status  the assessment's overall status, e.g. {@code COMPLIANT}
     * @param details short summary of what failed, or {@code "none"}
     * @return the result, always non-null
     */
    IseResult publishPosture(String mac, String status, String details);

    /**
     * Requests an enforcement action: an immediate CoA re-authentication
     * or ANC quarantine/clear, depending on configuration.
     *
     * @param mac    the endpoint's MAC address
     * @param action {@link EnforcementAction#RESTRICT} or {@link EnforcementAction#CLEAR}
     * @param policy ANC policy name override; {@code null} uses the configured default
     * @return the result, always non-null
     */
    IseResult publishEnforcement(String mac, EnforcementAction action, String policy);

    /** @return whether ISE currently answers a lightweight probe request */
    boolean reachable();
}