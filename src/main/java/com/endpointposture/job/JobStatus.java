package com.endpointposture.job;

/**
 * Lifecycle of a {@link PostureJob}.
 *
 * <p>{@code QUEUED} to {@code RUNNING} to {@code COMPLETE}; a failed attempt
 * goes back to {@code QUEUED} (with a backoff) while attempts remain, and
 * ends in {@code FAILED} once they are used up.</p>
 */
public enum JobStatus {
    /** Waiting to be claimed by a worker (possibly after a retry backoff). */
    QUEUED,
    /** Claimed by a worker and currently executing. */
    RUNNING,
    /** Finished successfully. */
    COMPLETE,
    /** Failed and out of retry attempts; left for a human to look at. */
    FAILED
}
