package com.endpointposture.workerruntime.api;

import java.time.Duration;
import java.util.Optional;

/** An HTTP error from the backend. Messages never contain the worker key or request bodies. */
public class WorkerApiException extends RuntimeException {
    private final int status;

    public WorkerApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** 401 on an authenticated call: key unknown, malformed, or the worker was revoked. */
    public static final class Unauthorized extends WorkerApiException {
        public Unauthorized(String message) { super(401, message); }
    }

    /** 401 on enrollment: token invalid, expired or already used. */
    public static final class EnrollmentRejected extends WorkerApiException {
        public EnrollmentRejected(String message) { super(401, message); }
    }

    /** 409: the attempt is stale, expired or not owned by this worker. Drop the result; never retry it. */
    public static final class StaleAttempt extends WorkerApiException {
        public StaleAttempt(String message) { super(409, message); }
    }

    /** 429: back off, honoring Retry-After when the server sent one. */
    public static final class RateLimited extends WorkerApiException {
        private final Duration retryAfter;

        public RateLimited(String message, Duration retryAfter) {
            super(429, message);
            this.retryAfter = retryAfter;
        }

        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }
    }
}