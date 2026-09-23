package com.endpointposture.ise;

/**
 * Outcome of one call to Cisco ISE — either publishing a posture fact or
 * requesting an enforcement action.
 *
 * @param success whether ISE accepted/confirmed the operation
 * @param detail  human-readable detail: a confirmation string on success,
 *                or the failure reason on failure. Never {@code null} —
 *                callers store this verbatim in the audit trail.
 */
public record IseResult(boolean success, String detail) {}