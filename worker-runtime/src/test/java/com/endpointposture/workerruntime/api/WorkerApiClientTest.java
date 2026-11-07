package com.endpointposture.workerruntime.api;

import com.endpointposture.workerruntime.TestServer;
import com.endpointposture.workerruntime.WorkerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkerApiClientTest {
    private static final String KEY = "epw_testkey123";
    private static final String P = "/api/v1/workers";
    private final ObjectMapper json = new ObjectMapper();
    private final UUID jobId = UUID.randomUUID();

    private WorkerConfig cfg(URI uri) {
        return new WorkerConfig(uri, Path.of("x"), null, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private WorkerApiClient auth(TestServer s) {
        return WorkerApiClient.authenticated(cfg(s.uri()), KEY);
    }

    @Test
    void enrollSendsTheTokenWithoutAKeyAndParsesTheResult() throws Exception {
        UUID id = UUID.randomUUID();
        try (TestServer s = new TestServer()) {
            s.on(P + "/enroll", 200, "{\"workerId\":\"" + id + "\",\"name\":\"w1\",\"shard\":\"default\",\"apiKey\":\"epw_abc\"}");
            Enrolled e = WorkerApiClient.unauthenticated(cfg(s.uri())).enroll("ept_tok");

            assertEquals(id, e.workerId());
            assertEquals("epw_abc", e.apiKey());
            assertFalse(e.toString().contains("epw_abc"));
            TestServer.Recorded r = s.requests.get(0);
            assertEquals("POST", r.method());
            assertNull(r.workerKey());
            assertEquals("ept_tok", json.readTree(r.body()).get("token").asText());
        }
    }

    @Test
    void enrollRejectionAndRateLimitAreTyped() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(P + "/enroll", 401, "{\"message\":\"Invalid, expired or already used enrollment token\"}");
            var ex = assertThrows(WorkerApiException.EnrollmentRejected.class,
                    () -> WorkerApiClient.unauthenticated(cfg(s.uri())).enroll("ept_x"));
            assertTrue(ex.getMessage().contains("already used"));
        }
        try (TestServer s = new TestServer()) {
            s.on(P + "/enroll", 429, "{\"message\":\"Too many requests\"}", Map.of("Retry-After", "7"));
            var ex = assertThrows(WorkerApiException.RateLimited.class,
                    () -> WorkerApiClient.unauthenticated(cfg(s.uri())).enroll("ept_x"));
            assertEquals(Duration.ofSeconds(7), ex.retryAfter().orElseThrow());
        }
    }

    @Test
    void retryAfterParsesDeltaSecondsAndHttpDatesSafely() {
        Instant now = Instant.parse("2026-11-07T12:00:00Z");
        String future = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(45).atZone(ZoneOffset.UTC));
        String expired = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.minusSeconds(10).atZone(ZoneOffset.UTC));

        assertEquals(Duration.ofSeconds(7), WorkerApiClient.parseRetryAfter("7", now));
        assertEquals(Duration.ofSeconds(45), WorkerApiClient.parseRetryAfter(future, now));
        assertEquals(Duration.ZERO, WorkerApiClient.parseRetryAfter(expired, now));
        assertEquals(Duration.ZERO, WorkerApiClient.parseRetryAfter("0", now));
        assertEquals(Duration.ZERO, WorkerApiClient.parseRetryAfter("-5", now));
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE),
                WorkerApiClient.parseRetryAfter(Long.toString(Long.MAX_VALUE), now));
        assertNull(WorkerApiClient.parseRetryAfter("not-a-date", now));
        assertNull(WorkerApiClient.parseRetryAfter("999999999999999999999999999999", now));
        assertNull(WorkerApiClient.parseRetryAfter(null, now));
    }

    @Test
    void heartbeatSendsTheKeyAndParses() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(P + "/heartbeat", 200,
                    "{\"status\":\"ACTIVE\",\"lastSeenAt\":\"2026-11-06T10:00:00Z\",\"heartbeatIntervalSeconds\":30,\"staleAfterSeconds\":90}");
            HeartbeatResponse hb = auth(s).heartbeat();
            assertEquals("ACTIVE", hb.status());
            assertEquals(30, hb.heartbeatIntervalSeconds());
            assertEquals(Instant.parse("2026-11-06T10:00:00Z"), hb.lastSeenAt());
            assertEquals(KEY, s.requests.get(0).workerKey());
            assertEquals("", s.requests.get(0).body());
        }
    }

    @Test
    void unauthorizedIsTyped() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(P + "/heartbeat", 401, "Invalid worker key");
            var ex = assertThrows(WorkerApiException.Unauthorized.class, () -> auth(s).heartbeat());
            assertEquals("Invalid worker key", ex.getMessage());
            assertFalse(ex.getMessage().contains(KEY));
        }
    }

    @Test
    void claimReturnsEmptyOn204AndTheJobOn200() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(P + "/jobs/claim", 204, "");
            assertTrue(auth(s).claim().isEmpty());
        }
        try (TestServer s = new TestServer()) {
            s.on(P + "/jobs/claim", 200, "{\"jobId\":\"" + jobId + "\",\"attempt\":2,\"jobType\":\"POSTURE_CHECK\","
                    + "\"spec\":{\"kind\":\"posture\",\"computerName\":\"10.0.0.5\"},\"leaseExpiresAt\":\"2026-11-06T10:05:00Z\"}");
            ClaimedJob j = auth(s).claim().orElseThrow();
            assertEquals(jobId, j.jobId());
            assertEquals(2, j.attempt());
            assertEquals("POSTURE_CHECK", j.jobType());
            assertEquals("10.0.0.5", j.spec().get("computerName").asText());
            assertEquals(Instant.parse("2026-11-06T10:05:00Z"), j.leaseExpiresAt());
        }
    }

    @Test
    void renewSendsAttemptAndOptionalExtensionAndReturnsTheExpiry() throws Exception {
        String path = P + "/jobs/" + jobId + "/lease/renew";
        try (TestServer s = new TestServer()) {
            s.on(path, 200, "{\"accepted\":true,\"leaseExpiresAt\":\"2026-11-06T10:10:00Z\"}");
            assertEquals(Instant.parse("2026-11-06T10:10:00Z"), auth(s).renewLease(jobId, 3, null));
            JsonNode body = json.readTree(s.requests.get(0).body());
            assertEquals(3, body.get("attempt").asInt());
            assertFalse(body.has("extensionSeconds"));
        }
        try (TestServer s = new TestServer()) {
            s.on(path, 200, "{\"accepted\":true,\"leaseExpiresAt\":\"2026-11-06T10:10:00Z\"}");
            auth(s).renewLease(jobId, 3, 120);
            assertEquals(120, json.readTree(s.requests.get(0).body()).get("extensionSeconds").asInt());
        }
    }

    @Test
    void staleAttemptsAre409AndTyped() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(P + "/jobs/" + jobId + "/lease/renew", 409, "{\"message\":\"Attempt is stale\"}");
            s.on(P + "/jobs/" + jobId + "/complete", 409, "{\"message\":\"Attempt is stale\"}");
            s.on(P + "/jobs/" + jobId + "/fail", 409, "{\"message\":\"Attempt is stale\"}");
            assertThrows(WorkerApiException.StaleAttempt.class, () -> auth(s).renewLease(jobId, 1, null));
            assertThrows(WorkerApiException.StaleAttempt.class, () -> auth(s).complete(jobId, 1));
            assertThrows(WorkerApiException.StaleAttempt.class, () -> auth(s).fail(jobId, 1, "x"));
        }
    }

    @Test
    void completeAndFailSendTheExpectedBodies() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(P + "/jobs/" + jobId + "/complete", 200, "{\"accepted\":true}");
            s.on(P + "/jobs/" + jobId + "/fail", 200, "{\"accepted\":true}");
            auth(s).complete(jobId, 4);
            auth(s).fail(jobId, 4, "WinRM refused");
            assertEquals(4, json.readTree(s.requests.get(0).body()).get("attempt").asInt());
            JsonNode fail = json.readTree(s.requests.get(1).body());
            assertEquals(4, fail.get("attempt").asInt());
            assertEquals("WinRM refused", fail.get("reason").asText());
        }
    }

    @Test
    void otherErrorsKeepTheirStatus() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(P + "/heartbeat", 500, "{\"error\":\"boom\"}");
            WorkerApiException ex = assertThrows(WorkerApiException.class, () -> auth(s).heartbeat());
            assertEquals(500, ex.status());
            assertEquals(WorkerApiException.class, ex.getClass());
        }
    }

    @Test
    void redirectsAreNeverFollowedSoTheKeyCannotLeak() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(P + "/heartbeat", 302, "", Map.of("Location", "http://127.0.0.1:1/steal"));
            WorkerApiException ex = assertThrows(WorkerApiException.class, () -> auth(s).heartbeat());
            assertEquals(302, ex.status());
            assertEquals(1, s.requests.size());
        }
    }

    @Test
    void unreachableBackendIsATransportError() throws Exception {
        URI dead;
        try (TestServer s = new TestServer()) {
            dead = s.uri();
        }
        assertThrows(WorkerTransportException.class,
                () -> WorkerApiClient.authenticated(cfg(dead), KEY).heartbeat());
    }

    @Test
    void authenticatedCallsNeedAKeyAndToStringHidesIt() throws Exception {
        try (TestServer s = new TestServer()) {
            assertThrows(IllegalStateException.class,
                    () -> WorkerApiClient.unauthenticated(cfg(s.uri())).heartbeat());
            assertThrows(IllegalArgumentException.class, () -> WorkerApiClient.authenticated(cfg(s.uri()), " "));
            assertFalse(auth(s).toString().contains(KEY));
        }
    }
}