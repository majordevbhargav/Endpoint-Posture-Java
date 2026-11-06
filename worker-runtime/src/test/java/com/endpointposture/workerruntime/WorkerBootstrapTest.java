package com.endpointposture.workerruntime;

import com.endpointposture.workerruntime.api.WorkerApiClient;
import com.endpointposture.workerruntime.credentials.CredentialStore;
import com.endpointposture.workerruntime.credentials.FakeProtector;
import com.endpointposture.workerruntime.credentials.StoredCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkerBootstrapTest {
    private static final String ENROLL = "/api/v1/workers/enroll";

    @TempDir Path dir;
    private final UUID id = UUID.randomUUID();

    private WorkerConfig cfg(TestServer s, String token) {
        return new WorkerConfig(s.uri(), dir.resolve("state.json"), token, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private CredentialStore store() {
        return new CredentialStore(dir.resolve("state.json"), new FakeProtector());
    }

    private WorkerBootstrap bootstrap(TestServer s, String token, CredentialStore store) {
        WorkerConfig c = cfg(s, token);
        return new WorkerBootstrap(c, store, WorkerApiClient.unauthenticated(c));
    }

    private String enrolledJson() {
        return "{\"workerId\":\"" + id + "\",\"name\":\"w1\",\"shard\":\"default\",\"apiKey\":\"epw_enrolled-key\"}";
    }

    @Test
    void notEnrolledWithoutATokenFailsWithoutCallingTheServer() throws Exception {
        try (TestServer s = new TestServer()) {
            var ex = assertThrows(WorkerBootstrap.BootstrapException.class,
                    () -> bootstrap(s, null, store()).ensureEnrolled());
            assertTrue(ex.getMessage().contains("EP_ENROLLMENT_TOKEN"));
            assertTrue(s.requests.isEmpty());
        }
    }

    @Test
    void enrollsOnceSavesTheKeyAndReusesItAfterwards() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(ENROLL, 200, enrolledJson());

            WorkerBootstrap.Result first = bootstrap(s, "ept_tok", store()).ensureEnrolled();
            assertTrue(first.newlyEnrolled());
            assertEquals("epw_enrolled-key", first.credentials().apiKey());

            WorkerBootstrap.Result second = bootstrap(s, "ept_tok", store()).ensureEnrolled();
            assertFalse(second.newlyEnrolled());
            assertEquals(id, second.credentials().workerId());
            assertEquals(1, s.requests.size());
        }
    }

    @Test
    void aRejectedTokenSavesNothing() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(ENROLL, 401, "{\"message\":\"Invalid, expired or already used enrollment token\"}");
            var ex = assertThrows(WorkerBootstrap.BootstrapException.class,
                    () -> bootstrap(s, "ept_bad", store()).ensureEnrolled());
            assertTrue(ex.getMessage().contains("rejected"));
            assertFalse(Files.exists(dir.resolve("state.json")));
        }
    }

    @Test
    void storedCredentialsForAnotherServerAreNeverUsed() throws Exception {
        try (TestServer s = new TestServer()) {
            store().save(new StoredCredentials(id, "w1", "default", URI.create("http://other-host:8090"), "epw_enrolled-key"));
            var ex = assertThrows(WorkerBootstrap.BootstrapException.class,
                    () -> bootstrap(s, null, store()).ensureEnrolled());
            assertTrue(ex.getMessage().contains("Refusing"));
            assertTrue(s.requests.isEmpty());
        }
    }

    @Test
    void ifTheKeyCannotBeSavedTheErrorExplainsTheSpentToken() throws Exception {
        try (TestServer s = new TestServer()) {
            s.on(ENROLL, 200, enrolledJson());
            Path blocker = Files.createFile(dir.resolve("afile"));
            CredentialStore bad = new CredentialStore(blocker.resolve("state.json"), new FakeProtector());

            var ex = assertThrows(WorkerBootstrap.BootstrapException.class,
                    () -> bootstrap(s, "ept_tok", bad).ensureEnrolled());
            assertTrue(ex.getMessage().contains(id.toString()));
            assertTrue(ex.getMessage().contains("revoke"));
            assertFalse(ex.getMessage().contains("epw_enrolled-key"));
        }
    }

    @Test
    void originComparisonUnderstandsDefaultPorts() {
        assertTrue(WorkerBootstrap.sameOrigin(URI.create("http://h"), URI.create("http://H:80")));
        assertTrue(WorkerBootstrap.sameOrigin(URI.create("https://h"), URI.create("https://h:443")));
        assertFalse(WorkerBootstrap.sameOrigin(URI.create("http://h"), URI.create("https://h")));
        assertFalse(WorkerBootstrap.sameOrigin(URI.create("http://h:1"), URI.create("http://h:2")));
    }
}