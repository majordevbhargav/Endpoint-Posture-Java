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

/** Persistent control plane. Blocking execution is delegated to an independent job thread. */
public final class WorkerLifecycle {
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    @FunctionalInterface
    public interface JobHandler {
        void handle(ClaimedJob job) throws Exception;

        default boolean isBusy() {
            return false;
        }
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

    WorkerLifecycle(
            WorkerApiClient api,
            JobHandler jobHandler,
            Sleeper sleeper,
            PrintStream err,
            DoubleSupplier random) {
        this.api = api;
        this.jobHandler = jobHandler;
        this.sleeper = sleeper;
        this.err = err;
        this.random = random;
    }

    /** 0 orderly stop; 4 revoked credentials; 5 permanent API/handler error. */
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

                if (jobHandler != null && !jobHandler.isBusy()) {
                    Optional<ClaimedJob> claimed = api.claim();
                    if (claimed.isPresent()) {
                        jobHandler.handle(claimed.get());
                        // A handler may launch an asynchronous job. The next iteration heartbeats,
                        // but will not claim again until isBusy() becomes false.
                        nextDelay = jobHandler.isBusy()
                                ? Duration.ofSeconds(heartbeat.heartbeatIntervalSeconds())
                                : Duration.ZERO;
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
                Duration serverDelay = e.retryAfter().orElse(null);
                nextDelay = serverDelay == null || serverDelay.isZero()
                        ? jitteredBackoff(backoff)
                        : jitteredServerDelay(serverDelay);
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
                if (Thread.currentThread().isInterrupted()) {
                    return 0;
                }
                nextDelay = jitteredBackoff(backoff);
                backoff = nextBackoff(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            } catch (Exception e) {
                err.println("Worker job handler failed: " + safeMessage(e));
                return 5;
            }
        }
    }

    private static boolean isTransientStatus(int status) {
        return status == 408 || status == 425 || status >= 500;
    }

    private Duration jitteredBackoff(Duration base) {
        return capped(Duration.ofMillis((long) (base.toMillis() * (0.5 + 0.5 * sampleRandom()))));
    }

    private Duration jitteredServerDelay(Duration requested) {
        Duration base = capped(requested);
        long remaining = MAX_BACKOFF.minus(base).toMillis();
        if (remaining <= 0) {
            return MAX_BACKOFF;
        }
        long limit = Math.min(remaining, (long) (base.toMillis() * MAX_JITTER_FRACTION));
        return base.plusMillis((long) (limit * sampleRandom()));
    }

    private double sampleRandom() {
        double value = random.getAsDouble();
        if (Double.isNaN(value) || value < 0) {
            return 0;
        }
        return Math.min(value, 1.0);
    }

    private static Duration capped(Duration delay) {
        if (delay.isNegative() || delay.isZero()) {
            return Duration.ZERO;
        }
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private static Duration nextBackoff(Duration current) {
        return current.compareTo(MAX_BACKOFF.dividedBy(2)) >= 0
                ? MAX_BACKOFF
                : current.multipliedBy(2);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
