package com.endpointposture.posture;

/**
 * Outcome of a posture assessment, and of each individual check inside it.
 *
 * <p>Shared by both {@code assessment.status} and {@code check_result.status} -
 * same three-value outcome at both the whole-assessment level and the
 * individual-check level, so one enum covers both.</p>
 *
 * <p><b>Order matters:</b> constants are declared from least to most severe,
 * and {@link PostureIngestService} relies on that ({@code ordinal()}) to pick
 * the worst status when combining checks. Do not reorder them.</p>
 */
public enum AssessmentStatus {
    /** Everything checked met the requirement. */
    COMPLIANT,
    /** At least one requirement was not met (for example, a firewall profile is off). */
    NON_COMPLIANT,
    /** The check could not be completed or reported; says nothing about compliance. */
    ERROR
}
