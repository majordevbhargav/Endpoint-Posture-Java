package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.api.WorkerApiClient;
import com.endpointposture.workerruntime.api.WorkerApiException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkerLifecycleTest {
    private static final String P = "/api/v1/workers";
    private static final String KEY = "epw_lifecycle-test-key";
    private static final String HEARTBEAT =
            "{\"status\":\"ACTIVE\",\"lastSeenAt\":\"2026-11-06T10:00:00Z\","
                    + "\"heartbeatIntervalSeconds\":30,\"staleAfterSeconds\":90}";

    private WorkerLifecycle lifecycle(TestServer server, WorkerLifecycle.JobHandler handler,
                                      WorkerLifecycle.Sleeper sleeper, ByteArrayOutputStream errors) {
        WorkerConfig config = new WorkerConfig(server.uri(), java.nio.file.Path.of("unused-state.json"),
                null, Duration.ofSeconds(2), Duration.ofSeconds(2));
        return new WorkerLifecycle(WorkerApiClient.authenticated(config, KEY), handler, sleeper,
                new PrintStream(errors));
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
    void serverErrorsUseExponentialBackoffThenRecover() throws Exception {
        try (TestServer server = new TestServer()) {
            server.onSequence(P + "/heartbeat", new int[]{503, 200}, HEARTBEAT);
            List<Duration> sleeps = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            int exit = lifecycle(server, null, delay -> {
                sleeps.add(delay);
                if (calls.incrementAndGet() == 2) throw new InterruptedException("stop");
            }, new ByteArrayOutputStream()).run();

            assertEquals(0, exit);
            assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(30)), sleeps);
            assertEquals(2, server.requests.size());
        }
    }

    @Test
    void rateLimitHonorsRetryAfter() throws Exception {
        try (TestServer server = new TestServer()) {
            server.on(P + "/heartbeat", 429, "{\"message\":\"slow down\"}",
                    java.util.Map.of("Retry-After", "7"));
            List<Duration> sleeps = new ArrayList<>();
            int exit = lifecycle(server, null, delay -> {
                sleeps.add(delay);
                throw new InterruptedException("stop");
            }, new ByteArrayOutputStream()).run();

            assertEquals(0, exit);
            assertEquals(List.of(Duration.ofSeconds(7)), sleeps);
            assertEquals(1, server.requests.size());
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
}
