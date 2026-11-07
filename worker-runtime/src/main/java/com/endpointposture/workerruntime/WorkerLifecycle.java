package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.api.ClaimedJob;
import com.endpointposture.workerruntime.api.HeartbeatResponse;
import com.endpointposture.workerruntime.api.WorkerApiClient;
import com.endpointposture.workerruntime.api.WorkerApiException;
import com.endpointposture.workerruntime.api.WorkerTransportException;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Long-running worker control loop. Heartbeat cadence comes from the backend, not a local
 * hard-coded timer. A job is only claimed when a handler is installed; this prevents PR6.2
 * from leasing work that the runtime cannot yet execute (the executor is introduced in PR6.3).
 *
 * <p>The handler is synchronous in this foundation slice. PR6.3 must not install a blocking
 * executor here: job execution must run independently from the control-plane heartbeat loop,
 * with bounded coordination and cancellation. PR6.4 will add lease renewal and ownership fencing.
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
    private static final double MAX_JITTER_FRACTION = 0.20;

    private final WorkerApiClient api;
    private final JobHandler jobHandler;
    private final Sleeper sleeper;
    private final PrintStream err;
    private final DoubleSupplier random;

    public WorkerLifecycle(WorkerApiClient api, JobHandler jobHandler, Sleeper sleeper, PrintStream err) {
        this(api, jobHandler, sleeper, err, () -> ThreadLocalRandom.current().nextDouble());
    }

    WorkerLifecycle(WorkerApiClient api, JobHandler jobHandler, Sleeper sleeper,
                    PrintStream err, DoubleSupplier random) {
        this.api = api;
        this.jobHandler = jobHandler;
        this.sleeper = sleeper;
        this.err = err;
        this.random = random;
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

                // Reset only after heartbeat, optional claim, and optional handler all succeed.
                backoff = INITIAL_BACKOFF;
            } catch (WorkerApiException.Unauthorized e) {
                err.println("The backend rejected this worker's key. The worker may have been revoked.");
                return 4;
            } catch (WorkerApiException.RateLimited e) {
                Duration serverDelay = e.retryAfter().orElse(null);
                nextDelay = serverDelay == null || serverDelay.isZero()
                        ? jitteredBackoff(backoff) : jitteredServerDelay(serverDelay);
                backoff = nextBackoff(backoff);
            } catch (WorkerApiException e) {
                if (isTransientStatus(e.status())) {
                    nextDelay = jitteredBackoff(backoff);
                    backoff = nextBackoff(backoff);
                } else {
                    err.println("Worker API rejected a request (HTTP " + e.status() + "): " + safeMessage(e));
                    return 5;
                }
            } catch (WorkerTransportException e) {
                if (Thread.currentThread().isInterrupted()) return 0;
                nextDelay = jitteredBackoff(backoff);
                backoff = nextBackoff(backoff);
            }
        }
    }

    private static boolean isTransientStatus(int status) {
        return status == 408 || status == 425 || status >= 500;
    }

    private Duration jitteredBackoff(Duration base) {
        // Equal jitter: [50%, 100%] of the exponential delay. This never exceeds the cap.
        double factor = 0.5 + (0.5 * sampleRandom());
        return capped(Duration.ofMillis((long) (base.toMillis() * factor)));
    }

    private Duration jitteredServerDelay(Duration requested) {
        // Never retry before Retry-After; add up to 20% positive jitter without exceeding 5m.
        Duration base = capped(requested);
        long remaining = MAX_BACKOFF.minus(base).toMillis();
        if (remaining <= 0) return MAX_BACKOFF;
        long jitterLimit = Math.min(remaining, (long) (base.toMillis() * MAX_JITTER_FRACTION));
        long jitter = (long) (jitterLimit * sampleRandom());
        return base.plusMillis(jitter);
    }

    private double sampleRandom() {
        double value = random.getAsDouble();
        if (Double.isNaN(value) || value < 0) return 0;
        return Math.min(value, 1.0);
    }

    private static Duration capped(Duration delay) {
        if (delay.isNegative() || delay.isZero()) return Duration.ZERO;
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private static Duration nextBackoff(Duration current) {
        return current.compareTo(MAX_BACKOFF.dividedBy(2)) >= 0
                ? MAX_BACKOFF : current.multipliedBy(2);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
