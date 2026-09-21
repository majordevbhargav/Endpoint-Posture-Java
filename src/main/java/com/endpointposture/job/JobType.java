package com.endpointposture.job;

/**
 * The kind of work a job represents. Modeled as an enum (mapped to a
 * plain TEXT column via {@code @Enumerated(EnumType.STRING)}) rather than
 * a free-form string, for compile-time safety - adding a new job type
 * later is a one-line addition here, no schema change required since
 * the column itself is just TEXT.
 */
public enum JobType {
    /** Run the posture agent (firewall, ports, applications) against an endpoint. */
    POSTURE_CHECK,

    /**
     * Run the hardware-health agent against an endpoint. The worker can
     * dispatch it, but the backend endpoint that receives hardware results
     * is not built yet, so these jobs will fail until it is.
     */
    HARDWARE_CHECK
}
