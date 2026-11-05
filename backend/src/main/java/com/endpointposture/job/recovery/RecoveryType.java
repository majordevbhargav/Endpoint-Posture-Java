package com.endpointposture.job.recovery;

/**
 * Distinguishes the failure or timeout trigger that caused a job to be recovered.
 */
public enum RecoveryType {
    /** Job ran longer than its configured process timeout plus margin without a heartbeat/report. */
    PROCESS_TIMEOUT,

    /** Remote worker lease lapsed without being renewed or completed. */
    LEASE_EXPIRED,

    /** Owning worker was explicitly revoked by an administrator while holding active jobs. */
    WORKER_REVOKED
}

