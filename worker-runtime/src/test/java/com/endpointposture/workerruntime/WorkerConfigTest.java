package com.endpointposture.workerruntime;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkerConfigTest {

    private WorkerConfig of(String url, String token) {
        return new WorkerConfig(URI.create(url), Path.of("state.json"), token,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void urlIsNormalizedAndAnEmptyPathIsAllowed() {
        assertEquals(URI.create("https://host:8090"), of("HTTPS://Host:8090/", null).backendUrl());
        assertEquals(URI.create("https://host"), of("https://host", null).backendUrl());
    }

    @Test
    void badUrlsAreRejected() {
        for (String bad : new String[] {"ftp://host", "localhost:8090", "http://u:p@host", "https://host/api",
                "https://host?x=1"}) {
            assertThrows(IllegalArgumentException.class, () -> of(bad, null), bad);
        }
    }

    @Test
    void nonLoopbackHttpIsRejectedButLoopbackHttpIsAllowed() {
        assertThrows(IllegalArgumentException.class, () -> of("http://backend:8090", null));
        assertEquals(URI.create("http://127.0.0.1:8090"), of("http://127.0.0.1:8090", null).backendUrl());
        assertEquals(URI.create("https://backend:8090"), of("https://backend:8090", null).backendUrl());
    }

    @Test
    void blankTokenBecomesNullAndToStringRedactsIt() {
        assertNull(of("https://h", "  ").enrollmentToken());
        WorkerConfig c = of("https://h", "ept_secret");
        assertFalse(c.toString().contains("ept_secret"));
        assertTrue(c.toString().contains("<redacted>"));
    }

    @Test
    void fromEnvironmentReadsEverything() {
        WorkerConfig c = WorkerConfig.fromEnvironment(Map.of(
                "EP_BACKEND_URL", "https://h:1", "EP_WORKER_STATE_FILE", "x/s.json",
                "EP_ENROLLMENT_TOKEN", "ept_t", "EP_HTTP_CONNECT_TIMEOUT_SECONDS", "3",
                "EP_HTTP_REQUEST_TIMEOUT_SECONDS", "4"));
        assertEquals(Path.of("x/s.json"), c.stateFile());
        assertEquals("ept_t", c.enrollmentToken());
        assertEquals(Duration.ofSeconds(3), c.connectTimeout());
        assertEquals(Duration.ofSeconds(4), c.requestTimeout());
    }

    @Test
    void defaultsAndErrors() {
        WorkerConfig c = WorkerConfig.fromEnvironment(Map.of("EP_BACKEND_URL", "https://h"));
        assertTrue(c.stateFile().endsWith("state.json"));
        assertEquals(Duration.ofSeconds(10), c.connectTimeout());
        assertThrows(IllegalArgumentException.class, () -> WorkerConfig.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> WorkerConfig.fromEnvironment(
                Map.of("EP_BACKEND_URL", "https://h", "EP_HTTP_CONNECT_TIMEOUT_SECONDS", "abc")));
        assertThrows(IllegalArgumentException.class, () -> WorkerConfig.fromEnvironment(
                Map.of("EP_BACKEND_URL", "https://h", "EP_HTTP_REQUEST_TIMEOUT_SECONDS", "0")));
    }
}