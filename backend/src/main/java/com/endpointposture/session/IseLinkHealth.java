package com.endpointposture.session;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks whether the ISE session poll is currently working, so "ISE is
 * unreachable" is an explicit state instead of being confused with
 * "no sessions".
 */
@Component
public class IseLinkHealth {

    /** Consecutive failures before ISE is reported as down (avoids flapping on one blip). */
    private static final int FAILURE_THRESHOLD = 2;

    private final AtomicInteger failures = new AtomicInteger();
    private volatile Instant lastSuccessAt;
    private volatile String lastError;

    public void success() {
        failures.set(0);
        lastSuccessAt = Instant.now();
        lastError = null;
    }

    public void failure(String error) {
        failures.incrementAndGet();
        lastError = error;
    }

    public boolean reachable() {
        return failures.get() < FAILURE_THRESHOLD;
    }

    public record Status(boolean reachable, Instant lastSuccessAt, String lastError) {}

    public Status status() {
        return new Status(reachable(), lastSuccessAt, lastError);
    }
}