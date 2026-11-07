package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.endpointposture.workerruntime.api.HeartbeatResponse;
import com.endpointposture.workerruntime.api.WorkerApiClient;
import com.endpointposture.workerruntime.api.WorkerApiException;
import com.endpointposture.workerruntime.api.WorkerTransportException;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Optional;

/**
 * Long-running worker control loop. Heartbeat cadence comes from the backend, not a local
 * hard-coded timer. A job is only claimed when a handler is installed; this prevents PR6.2
 * from leasing work that the runtime cannot yet execute (the executor is introduced in PR6.3).
 */
public final class WorkerLifecycle {
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    @FunctionalInterface
    public interface JobHandler {
        void handle(ClaimedJob job) throws Exception;
    }

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final WorkerApiClient api;
    private final JobHandler jobHandler;
    private final Sleeper sleeper;
    private final PrintStream err;

    public WorkerLifecycle(WorkerApiClient api, JobHandler jobHandler, Sleeper sleeper, PrintStream err) {
        this.api = api;
        this.jobHandler = jobHandler;
        this.sleeper = sleeper;
        this.err = err;
    }

    /** Returns 0 for an orderly stop, 4 for revoked credentials, and 5 for a permanent API error. */
    public int run() {
        Duration backoff = INITIAL_BACKOFF;
        Duration nextDelay = Duration.ZERO;
        while (true) {
            if (!nextDelay.isZero()) {
                try {
                    sleeper.sleep(nextDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }

            try {
                HeartbeatResponse heartbeat = api.heartbeat();
                if (heartbeat.heartbeatIntervalSeconds() < 1
                        || heartbeat.staleAfterSeconds() / 2 < heartbeat.heartbeatIntervalSeconds()) {
                    err.println("Backend returned invalid worker heartbeat timing; stopping.");
                    return 5;
                }

                if (jobHandler != null) {
                    Optional<ClaimedJob> claimed = api.claim();
                    if (claimed.isPresent()) {
                        try {
                            jobHandler.handle(claimed.get());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return 0;
                        } catch (Exception e) {
                            // The handler owns execution-specific failure reporting. Do not claim
                            // another job in a tight loop after an unexpected handler failure.
                            err.println("Worker job handler failed: " + safeMessage(e));
                            return 5;
                        }
                        nextDelay = Duration.ZERO;
                    } else {
                        nextDelay = Duration.ofSeconds(heartbeat.heartbeatIntervalSeconds());
                    }
                } else {
                    nextDelay = Duration.ofSeconds(heartbeat.heartbeatIntervalSeconds());
                }
                backoff = INITIAL_BACKOFF;
            } catch (WorkerApiException.Unauthorized e) {
                err.println("The backend rejected this worker's key. The worker may have been revoked.");
                return 4;
            } catch (WorkerApiException.RateLimited e) {
                nextDelay = e.retryAfter().filter(d -> !d.isZero()).orElse(backoff);
                backoff = nextBackoff(backoff);
            } catch (WorkerApiException e) {
                if (e.status() >= 500) {
                    nextDelay = backoff;
                    backoff = nextBackoff(backoff);
                } else {
                    err.println("Worker API rejected a request (HTTP " + e.status() + "): " + safeMessage(e));
                    return 5;
                }
            } catch (WorkerTransportException e) {
                nextDelay = backoff;
                backoff = nextBackoff(backoff);
            }
        }
        return 0;
    }

    private static Duration nextBackoff(Duration current) {
        return current.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : current.multipliedBy(2);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
