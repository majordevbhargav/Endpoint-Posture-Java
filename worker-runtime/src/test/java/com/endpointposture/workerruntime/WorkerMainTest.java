package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.credentials.FakeProtector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkerMainTest {
    private static final String P = "/api/v1/workers";
    private static final String KEY = "epw_main-test-key";

    @TempDir Path dir;
    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

    private int run(Map<String, String> env) {
        return WorkerMain.run(env, new PrintStream(outBuf), new PrintStream(errBuf), new FakeProtector());
    }

    private Map<String, String> env(TestServer s, String token) {
        Map<String, String> m = new HashMap<>();
        m.put("EP_BACKEND_URL", s.uri().toString());
        m.put("EP_WORKER_STATE_FILE", dir.resolve("state.json").toString());
        if (token != null) m.put("EP_ENROLLMENT_TOKEN", token);
        return m;
    }

    private void stubHappyPath(TestServer s) {
        s.on(P + "/enroll", 200, "{\"workerId\":\"" + UUID.randomUUID() + "\",\"name\":\"w1\",\"shard\":\"lab\",\"apiKey\":\"" + KEY + "\"}");
        s.on(P + "/heartbeat", 200,
                "{\"status\":\"ACTIVE\",\"lastSeenAt\":\"2026-11-06T10:00:00Z\",\"heartbeatIntervalSeconds\":30,\"staleAfterSeconds\":90}");
    }

    @Test
    void configurationErrorExits2() {
        assertEquals(2, run(Map.of()));
        assertTrue(errBuf.toString().contains("EP_BACKEND_URL"));
    }

    @Test
    void firstRunEnrollsThenLaterRunsReuseTheStoredKey() throws Exception {
        try (TestServer s = new TestServer()) {
            stubHappyPath(s);

            assertEquals(0, run(env(s, "ept_tok")));
            assertTrue(outBuf.toString().contains("Enrolled as worker 'w1' (shard lab)"));
            assertTrue(Files.exists(dir.resolve("state.json")));

            assertEquals(0, run(env(s, null)));
            assertTrue(outBuf.toString().contains("Using stored credentials"));

            long enrolls = s.requests.stream().filter(r -> r.path().endsWith("/enroll")).count();
            long heartbeats = s.requests.stream().filter(r -> r.path().endsWith("/heartbeat")).count();
            assertEquals(1, enrolls);
            assertEquals(2, heartbeats);
            assertEquals(KEY, s.requests.get(s.requests.size() - 1).workerKey());
            assertFalse(outBuf.toString().contains(KEY));
            assertFalse(errBuf.toString().contains(KEY));
        }
    }

    @Test
    void notEnrolledAndNoTokenExits3() throws Exception {
        try (TestServer s = new TestServer()) {
            assertEquals(3, run(env(s, null)));
        }
    }

    @Test
    void aRevokedKeyExits4() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(P + "/enroll", 200, "{\"workerId\":\"" + UUID.randomUUID() + "\",\"name\":\"w1\",\"shard\":\"lab\",\"apiKey\":\"" + KEY + "\"}");
            s.on(P + "/heartbeat", 401, "Invalid worker key");

            assertEquals(4, run(env(s, "ept_tok")));
            assertTrue(errBuf.toString().contains("revoked"));
        }
    }

    @Test
    void anUnreachableBackendDuringHeartbeatExits5() throws Exception {
        Map<String, String> env;
        try (TestServer s = new TestServer()) {
            stubHappyPath(s);
            assertEquals(0, run(env(s, "ept_tok")));
            env = env(s, null);
        }
        assertEquals(5, run(env));
    }
}