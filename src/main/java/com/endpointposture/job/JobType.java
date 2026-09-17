package com.endpointposture.job;

/**
 * The kind of work a job represents. Modeled as an enum (mapped to a
 * plain TEXT column via {@code @Enumerated(EnumType.STRING)}) rather than
 * a free-form string, for compile-time safety - adding a new job type
 * later (e.g. HARDWARE_HEALTH_CHECK) is a one-line addition here, no
 * schema change required since the column itself is just TEXT.
 */
public enum JobType {
    POSTURE_CHECK
}