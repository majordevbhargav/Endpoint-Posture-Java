package com.endpointposture.job;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs N worker threads that each loop: claim a job, run it, repeat, and
 * sleep only when the queue is empty. Concurrent claiming is safe because
 * the claim query uses FOR UPDATE SKIP LOCKED.
 *
 * <p>
 * Each running job starts a powershell.exe, so size
 * {@code app.jobs.worker-threads} to what the host can bear.
 * </p>
 *
 * <p>
 * If the backend stops mid-job, the interrupted job stays RUNNING until
 * {@link StaleJobRecoveryScheduler} recovers it.
 * </p>
 */
@Component
@ConditionalOnProperty(name = "app.jobs.workers.enabled", havingValue = "true", matchIfMissing = true)
public class JobWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(JobWorkerPool.class);

    private final JobWorker worker;
    private final int threads;
    private final long idleSleepMs;
    private volatile boolean running;
    private ExecutorService executor;

    public JobWorkerPool(JobWorker worker,
            @Value("${app.jobs.worker-threads:4}") int threads,
            @Value("${app.jobs.poll-interval-ms:3000}") long idleSleepMs) {
        this.worker = worker;
        this.threads = Math.max(1, threads);
        this.idleSleepMs = idleSleepMs;
    }

    /** @return the configured number of worker threads */
    public int getThreads() {
        return threads;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "job-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        executor = Executors.newFixedThreadPool(threads, tf);
        for (int i = 0; i < threads; i++) {
            executor.submit(this::loop);
        }
        log.info("Job worker pool started with {} thread(s)", threads);
    }

    private void loop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                boolean ranJob = worker.runOnce();
                if (!ranJob)
                    Thread.sleep(idleSleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Never let a worker thread die; back off and keep polling.
                log.error("Worker loop error (continuing)", e);
                try {
                    Thread.sleep(idleSleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (executor == null)
            return;
        executor.shutdownNow(); // interrupts workers; JobWorker kills any child powershell.exe
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}