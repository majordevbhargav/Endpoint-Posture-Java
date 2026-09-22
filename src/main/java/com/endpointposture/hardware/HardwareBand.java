package com.endpointposture.hardware;

/**
 * Overall health banding for a hardware health report.
 *
 * <p>Thresholds (85+/70+/50+/below) are illustrative defaults carried
 * over from the original project plan (Section 15, question 10) —
 * not yet confirmed against real fleet data. See {@link HardwareHealthService#bandFor(int)}.</p>
 */
public enum HardwareBand {
    HEALTHY, WARNING, DEGRADED, CRITICAL
}