package com.endpointposture.dashboard;

/** Response shapes for the dashboard APIs. */
public final class DashboardDtos {

    private DashboardDtos() {}

    /**
     * @param total        every known endpoint
     * @param connected    endpoints flagged connected (independent of posture)
     * @param notConnected total minus connected
     * @param compliant    latest assessment COMPLIANT
     * @param nonCompliant latest assessment NON_COMPLIANT
     * @param error        latest assessment ERROR
     * @param unassessed   endpoints with no assessment yet
     * @param stale        latest assessment older than 2x the posture recheck interval
     */
    public record Summary(long total, long connected, long notConnected, long compliant,
                          long nonCompliant, long error, long unassessed, long stale) {}

    /**
     * @param date             day, yyyy-MM-dd (UTC)
     * @param assessed         endpoints that had an assessment by the end of that day
     * @param compliantPercent share of those whose latest assessment was COMPLIANT; null if none
     */
    public record TrendPoint(String date, long assessed, Double compliantPercent) {}

    /**
     * @param checkType   e.g. FIREWALL, OPEN_PORTS, APPLICATIONS
     * @param total       endpoints whose latest assessment included this check
     * @param passing     of those, how many were COMPLIANT
     * @param passPercent passing / total * 100
     */
    public record CategoryRate(String checkType, long total, long passing, double passPercent) {}
}