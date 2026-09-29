package com.endpointposture.system;

import java.time.Instant;
import java.util.List;

/** Response shapes for the system health API. */
public final class SystemHealthDtos {

    private SystemHealthDtos() {}

    /**
     * @param reachable whether a trivial query against PostgreSQL succeeded
     * @param error     failure reason when not reachable, otherwise {@code null}
     */
    public record Database(boolean reachable, String error) {}

    /**
     * @param reachable     whether the ISE session poll is currently working
     * @param lastSuccessAt last successful poll, or {@code null} if none yet
     * @param lastError     latest poll failure reason, or {@code null}
     */
    public record Ise(boolean reachable, Instant lastSuccessAt, String lastError) {}

    /**
     * @param queued                 jobs waiting to be claimed (includes retries in backoff)
     * @param running                jobs currently executing
     * @param complete               jobs finished successfully
     * @param failed                 jobs that ran out of attempts
     * @param oldestQueuedAgeSeconds how long the oldest queued job has been eligible; {@code null} if none queued
     * @param failedLast24h          jobs that ended FAILED in the last 24 hours
     */
    public record Queue(long queued, long running, long complete, long failed,
                        Long oldestQueuedAgeSeconds, long failedLast24h) {}

    /**
     * @param enabled whether the worker pool bean exists ({@code app.jobs.workers.enabled})
     * @param threads configured worker threads; 0 when the pool is disabled
     */
    public record Workers(boolean enabled, int threads) {}

    /**
     * @param status    {@code UP}, {@code DEGRADED} or {@code DOWN}
     * @param checkedAt when this snapshot was taken
     * @param warnings  human-readable reasons the status is not UP
     */
    public record SystemHealth(String status, Instant checkedAt, Database database, Ise ise,
                               Queue queue, Workers workers, List<String> warnings) {}
}