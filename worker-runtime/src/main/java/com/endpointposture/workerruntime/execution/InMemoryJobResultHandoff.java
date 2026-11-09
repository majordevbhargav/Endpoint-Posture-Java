package com.endpointposture.workerruntime.execution;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded in-process handoff used until PR6.4 wires an authenticated reporter.
 * Results remain retrievable after the execution thread ends; overflow fails closed.
 */
public final class InMemoryJobResultHandoff implements JobResultHandoff {
    private final BlockingQueue<CompletedJobResult> completed;

    public InMemoryJobResultHandoff() { this(32); }

    public InMemoryJobResultHandoff(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Handoff capacity must be positive");
        completed = new ArrayBlockingQueue<>(capacity);
    }

    @Override public void accept(CompletedJobResult result) {
        if (!completed.offer(result)) {
            throw new IllegalStateException("Completed-result handoff is full; refusing to discard a report");
        }
    }

    @Override public boolean hasPendingResults() { return !completed.isEmpty(); }

    public CompletedJobResult poll() { return completed.poll(); }
    public CompletedJobResult poll(long timeout, TimeUnit unit) throws InterruptedException {
        return completed.poll(timeout, unit);
    }
    public int size() { return completed.size(); }
}