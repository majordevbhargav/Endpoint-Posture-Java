package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.api.WorkerApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkerLifecycleTest {
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private static final String P = "/api/v1/workers";
    private static final String KEY = "epw_lifecycle-test-key";
    private static final String HEARTBEAT =
            "{\"status\":\"ACTIVE\",\"lastSeenAt\":\"2026-11-06T10:00:00Z\","
                    + "\"heartbeatIntervalSeconds\":30,\"staleAfterSeconds\":90}";
    private static final String ERROR = "{\"message\":\"temporary failure\"}";

    private WorkerLifecycle lifecycle(TestServer server, WorkerLifecycle.JobHandler handler,
                                      WorkerLifecycle.Sleeper sleeper, ByteArrayOutputStream errors) {
        return lifecycle(server, handler, sleeper, errors, () -> 1.0);
    }

    private WorkerLifecycle lifecycle(TestServer server, WorkerLifecycle.JobHandler handler,
                                      WorkerLifecycle.Sleeper sleeper, ByteArrayOutputStream errors,
                                      java.util.function.DoubleSupplier random) {
        WorkerConfig config = new WorkerConfig(server.uri(), java.nio.file.Path.of("unused-state.json"),
                null, Duration.ofSeconds(2), Duration.ofSeconds(2));
        return new WorkerLifecycle(WorkerApiClient.authenticated(config, KEY), handler, sleeper,
                new PrintStream(errors), random);
    }

    @Test
    void heartbeatUsesServerCadenceAndStopsCleanlyOnInterrupt() throws Exception {
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 200, HEARTBEAT);
            List<Duration> sleeps = new ArrayList<>();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();

            int exit = lifecycle(server, null, delay -> {
                sleeps.add(delay);
                throw new InterruptedException("stop");
            }, errors).run();

            assertEquals(0, exit);
            assertEquals(List.of(Duration.ofSeconds(30)), sleeps);
            assertEquals(1, server.requests.size());
            assertTrue(errors.toString().isBlank());
            assertTrue(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    void revokedCredentialsStopWithoutRetrying() throws Exception {
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 401, "{\"message\":\"revoked\"}");
            AtomicInteger sleeps = new AtomicInteger();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();

            int exit = lifecycle(server, null, delay -> sleeps.incrementAndGet(), errors).run();

            assertEquals(4, exit);
            assertEquals(0, sleeps.get());
            assertTrue(errors.toString().contains("revoked"));
            assertEquals(1, server.requests.size());
        }
    }

    @Test
    void successfulHeartbeatDoesNotResetBackoffWhenClaimKeepsFailing() throws Exception {
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 200, HEARTBEAT);
            server.onSequence(P + "/jobs/claim", new int[]{503, 503, 204},
                    new String[]{ERROR, ERROR, ""});
            List<Duration> sleeps = new ArrayList<>();
            AtomicInteger sleepCount = new AtomicInteger();

            int exit = lifecycle(server, job -> fail("no job should be returned"),
                    delay -> {
                        sleeps.add(delay);
                        if (sleepCount.incrementAndGet() == 3) throw new InterruptedException("stop");
                    }, new ByteArrayOutputStream()).run();

            assertEquals(0, exit);
            assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(30)), sleeps);
            assertEquals(3, server.requests.stream().filter(r -> r.path().endsWith("/heartbeat")).count());
            assertEquals(3, server.requests.stream().filter(r -> r.path().endsWith("/jobs/claim")).count());
        }
    }

    @Test
    void claimRateLimitHonorsRetryAfterWithoutRetryingEarly() throws Exception {
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 200, HEARTBEAT);
            server.onSequence(P + "/jobs/claim", new int[]{429, 204},
                    new String[]{"{\"message\":\"slow down\"}", ""}, Map.of("Retry-After", "7"));
            // The first delay is exactly the server minimum when random=0; the next is normal cadence.
            List<Duration> sleeps = new ArrayList<>();
            int exit = lifecycle(server, job -> fail("204 should not dispatch a job"), delay -> {
                sleeps.add(delay);
                if (sleeps.size() == 2) throw new InterruptedException("stop");
            }, new ByteArrayOutputStream(), () -> 0.0).run();

            assertEquals(0, exit);
            assertEquals(Duration.ofSeconds(30), sleeps.get(1));
            assertTrue(sleeps.get(0).compareTo(Duration.ofSeconds(7)) >= 0);
            assertTrue(sleeps.get(0).compareTo(Duration.ofSeconds(9)) <= 0);
        }
    }

    @Test
    void zeroAndInvalidRetryAfterFallBackToJitteredBackoff() throws Exception {
        for (String retryAfter : new String[]{"0", "not-a-date"}) {
            try (TestServer server = new TestServer()) {
                server.on(P + "/heartbeat", 429, ERROR, Map.of("Retry-After", retryAfter));
                List<Duration> sleeps = new ArrayList<>();
                int exit = lifecycle(server, null, delay -> {
                    sleeps.add(delay);
                    throw new InterruptedException("stop");
                }, new ByteArrayOutputStream(), () -> 0.0).run();

                assertEquals(0, exit);
                assertEquals(List.of(Duration.ofMillis(500)), sleeps);
            }
        }
    }

    @Test
    void exponentialBackoffUsesInjectedEqualJitter() throws Exception {
        for (double random : new double[]{0.0, 1.0}) {
            try (TestServer server = new TestServer()) {
                server.onSequence(P + "/heartbeat", new int[]{503, 200},
                        new String[]{ERROR, HEARTBEAT});
                List<Duration> sleeps = new ArrayList<>();
                int exit = lifecycle(server, null, delay -> {
                    sleeps.add(delay);
                    if (sleeps.size() == 2) throw new InterruptedException("stop");
                }, new ByteArrayOutputStream(), () -> random).run();

                assertEquals(0, exit);
                assertEquals(random == 0.0 ? Duration.ofMillis(500) : Duration.ofSeconds(1), sleeps.get(0));
            }
        }
    }

    @Test
    void excessivelyLargeRetryAfterIsCappedAtFiveMinutes() throws Exception {
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 429, ERROR,
                    Map.of("Retry-After", Long.toString(Long.MAX_VALUE)));
            List<Duration> sleeps = new ArrayList<>();
            int exit = lifecycle(server, null, delay -> {
                sleeps.add(delay);
                throw new InterruptedException("stop");
            }, new ByteArrayOutputStream(), () -> 1.0).run();

            assertEquals(0, exit);
            assertEquals(List.of(Duration.ofMinutes(5)), sleeps);
        }
    }

    @Test
    void transient408And425AreRetriedButStaleAttempt409IsPermanent() throws Exception {
        for (int transientStatus : new int[]{408, 425}) {
            try (TestServer server = new TestServer()) {
                server.onSequence(P + "/heartbeat", new int[]{transientStatus, 200},
                        new String[]{ERROR, HEARTBEAT});
                List<Duration> sleeps = new ArrayList<>();
                int exit = lifecycle(server, null, delay -> {
                    sleeps.add(delay);
                    if (sleeps.size() == 2) throw new InterruptedException("stop");
                }, new ByteArrayOutputStream()).run();
                assertEquals(0, exit);
                assertEquals(Duration.ofSeconds(1), sleeps.get(0));
            }
        }
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 200, HEARTBEAT);
            server.on(P + "/jobs/claim", 409, "{\"message\":\"Attempt is stale\"}");
            int exit = lifecycle(server, job -> fail("409 must not dispatch a job"),
                    delay -> fail("409 must not retry"), new ByteArrayOutputStream()).run();
            assertEquals(5, exit);
        }
    }

    @Test
    void backoffNeverExceedsFiveMinutes() throws Exception {
        try (TestServer server = new TestServer()) {
            int failures = 10;
            int[] statuses = new int[failures + 1];
            String[] bodies = new String[failures + 1];
            java.util.Arrays.fill(statuses, 0, failures, 503);
            java.util.Arrays.fill(bodies, 0, failures, ERROR);
            statuses[failures] = 200;
            bodies[failures] = HEARTBEAT;
            server.onSequence(P + "/heartbeat", statuses, bodies);
            List<Duration> sleeps = new ArrayList<>();
            int exit = lifecycle(server, null, delay -> {
                sleeps.add(delay);
                if (sleeps.size() == failures + 1) throw new InterruptedException("stop");
            }, new ByteArrayOutputStream()).run();

            assertEquals(0, exit);
            assertTrue(sleeps.stream().allMatch(d -> d.compareTo(Duration.ofMinutes(5)) <= 0));
            assertTrue(sleeps.contains(Duration.ofMinutes(5)));
        }
    }

    @Test
    void invalidHeartbeatTimingStopsImmediately() throws Exception {
        for (String invalid : new String[]{
                "{\"status\":\"ACTIVE\",\"lastSeenAt\":\"2026-11-06T10:00:00Z\","
                        + "\"heartbeatIntervalSeconds\":0,\"staleAfterSeconds\":90}",
                "{\"status\":\"ACTIVE\",\"lastSeenAt\":\"2026-11-06T10:00:00Z\","
                        + "\"heartbeatIntervalSeconds\":60,\"staleAfterSeconds\":90}"}) {
            try (TestServer server = new TestServer()) {
                server.on(P + "/heartbeat", 200, invalid);
                int exit = lifecycle(server, null, delay -> fail("invalid timing must stop"),
                        new ByteArrayOutputStream()).run();
                assertEquals(5, exit);
                assertEquals(1, server.requests.size());
            }
        }
    }

    @Test
    void interruptionDuringHttpActivityStopsGracefully() throws Exception {
        try (TestServer server = new TestServer()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.onBlocking(P + "/heartbeat", 200, HEARTBEAT, entered, release);
            AtomicInteger exit = new AtomicInteger(-1);
            Thread worker = new Thread(() -> exit.set(lifecycle(server, null,
                    delay -> fail("must not reach wait"), new ByteArrayOutputStream()).run()));
            worker.start();

            assertTrue(entered.await(3, TimeUnit.SECONDS), "heartbeat request did not reach test server");
            worker.interrupt();
            worker.join(3000);
            release.countDown();

            assertFalse(worker.isAlive(), "worker did not stop after HTTP interruption");
            assertEquals(0, exit.get());
        }
    }

    @Test
    void workerDoesNotClaimJobsUntilAnExecutionHandlerExists() throws Exception {
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 200, HEARTBEAT);
            int exit = lifecycle(server, null, delay -> {
                throw new InterruptedException("stop");
            }, new ByteArrayOutputStream()).run();

            assertEquals(0, exit);
            assertEquals(1, server.requests.size());
            assertTrue(server.requests.stream().noneMatch(r -> r.path().endsWith("/jobs/claim")));
        }
    }

    @Test
    void configuredHandlerReceivesClaimedJob() throws Exception {
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 200, HEARTBEAT);
            server.on(P + "/jobs/claim", 200,
                    "{\"jobId\":\"123e4567-e89b-12d3-a456-426614174000\",\"attempt\":1,"
                            + "\"jobType\":\"DIAGNOSTIC\",\"spec\":{},"
                            + "\"leaseExpiresAt\":\"2026-11-06T10:05:00Z\"}");
            AtomicInteger handled = new AtomicInteger();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();

            int exit = lifecycle(server, job -> {
                assertEquals(1, job.attempt());
                assertEquals("DIAGNOSTIC", job.jobType());
                handled.incrementAndGet();
                throw new InterruptedException("stop after one claim");
            }, delay -> fail("handler interruption should stop before sleeping"), errors).run();

            assertEquals(0, exit);
            assertEquals(1, handled.get());
            assertEquals(2, server.requests.size());
            assertEquals(KEY, server.requests.get(1).workerKey());
        }
    }


    @Test
    void blockedHandlerContinuesHeartbeatsWithoutClaimingAgain() throws Exception {
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 200, HEARTBEAT);
            String claim = "{\"jobId\":\"123e4567-e89b-12d3-a456-426614174000\",\"attempt\":1,"
                    + "\"jobType\":\"DIAGNOSTIC\",\"spec\":{},\"leaseExpiresAt\":\"2026-11-06T10:05:00Z\"}";
            server.on(P + "/jobs/claim", 200, claim);
            AtomicInteger handled = new AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean blocked = new java.util.concurrent.atomic.AtomicBoolean();
            AtomicInteger sleeps = new AtomicInteger();
            WorkerLifecycle.JobHandler handler = new WorkerLifecycle.JobHandler() {
                @Override public void handle(com.endpointposture.workerruntime.api.ClaimedJob job) {
                    handled.incrementAndGet();
                    blocked.set(true); // model PowerShellJobHandler's sticky failure state
                }
                @Override public boolean isBusy() { return blocked.get(); }
            };

            int exit = lifecycle(server, handler, delay -> {
                if (sleeps.incrementAndGet() == 3) throw new InterruptedException("stop");
            }, new ByteArrayOutputStream()).run();

            assertEquals(0, exit);
            assertEquals(1, handled.get());
            assertEquals(1, server.requests.stream().filter(r -> r.path().endsWith("/jobs/claim")).count());
            assertEquals(3, server.requests.stream().filter(r -> r.path().endsWith("/heartbeat")).count(),
                    "heartbeats must continue while blocked");
        }
    }

}
