package com.endpointposture.session;

import com.endpointposture.EndpointPostureApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The bulk SQL is Postgres-specific (unnest, ANY, CTE), so it is tested against a real Postgres. */
@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class SessionBatchWriterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("endpoint_posture_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("app.jwt.secret", () -> "testcontainers-only-secret-at-least-256-bits-long-value");
        r.add("app.seed-admin.password", () -> "test-admin-password");
        r.add("app.posture.api-key", () -> "test-agent-key");
        r.add("app.jobs.workers.enabled", () -> "false");
        r.add("app.jobs.recheck.enabled", () -> "false");
        r.add("app.retention.enabled", () -> "false");
    }

    static final String MAC = "AA:BB:CC:DD:EE:01";

    @Autowired SessionBatchWriter batch;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM posture_job");
        jdbc.update("DELETE FROM endpoint"); // cascades to the session log
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void aNewSessionCreatesTheEndpointALogRowAndExactlyOneJob() {
        batch.markConnected(Map.of(MAC, "10.0.0.9"));

        assertEquals(1, batch.enqueueReconnectChecks(List.of(MAC)));
        assertEquals(0, batch.enqueueReconnectChecks(List.of(MAC)), "already queued: no duplicate");

        assertEquals(1, count("SELECT count(*) FROM endpoint WHERE mac_address = ? AND connected", MAC));
        assertEquals(1, count("SELECT count(*) FROM endpoint_session_log WHERE event_type = 'CONNECTED'"));
        assertEquals(1, count("SELECT count(*) FROM posture_job WHERE status = 'QUEUED' AND priority = 0"));
    }

    @Test
    void aDeviceWithNeitherIpNorHostnameGetsNoJob() {
        Map<String, String> noIp = new HashMap<>();
        noIp.put(MAC, null);
        batch.markConnected(noIp);

        assertEquals(0, batch.enqueueReconnectChecks(List.of(MAC)));
    }

    @Test
    void aRecentlyCompletedCheckSuppressesTheReconnectJob() {
        batch.markConnected(Map.of(MAC, "10.0.0.9"));
        jdbc.update("""
                INSERT INTO posture_job (endpoint_id, job_type, status, completed_at)
                SELECT id, 'POSTURE_CHECK', 'COMPLETE', now() FROM endpoint WHERE mac_address = ?
                """, MAC);

        assertEquals(0, batch.enqueueReconnectChecks(List.of(MAC)));
    }

    @Test
    void disconnectingWritesOneLogRowAndIsIdempotent() {
        batch.markConnected(Map.of(MAC, "10.0.0.9"));

        assertEquals(1, batch.markDisconnected(List.of(MAC)));
        assertEquals(0, batch.markDisconnected(List.of(MAC)));

        assertEquals(0, count("SELECT count(*) FROM endpoint WHERE connected"));
        assertEquals(1, count("SELECT count(*) FROM endpoint_session_log WHERE event_type = 'DISCONNECTED'"));
    }

    @Test
    void reconnectingAnExistingDeviceKeepsItsRowAndKeepsItsIpWhenNoneIsGiven() {
        batch.markConnected(Map.of(MAC, "10.0.0.9"));
        batch.markDisconnected(List.of(MAC));

        Map<String, String> noIp = new HashMap<>();
        noIp.put(MAC, null);
        batch.markConnected(noIp);

        assertEquals(1, count("SELECT count(*) FROM endpoint"));
        assertEquals("10.0.0.9", jdbc.queryForObject(
                "SELECT ip_address FROM endpoint WHERE mac_address = ?", String.class, MAC));
    }

    @Test
    void ipChangesAreStored() {
        batch.markConnected(Map.of(MAC, "10.0.0.9"));
        batch.updateIps(Map.of(MAC, "10.0.0.10"));

        assertEquals("10.0.0.10", jdbc.queryForObject(
                "SELECT ip_address FROM endpoint WHERE mac_address = ?", String.class, MAC));
    }

    @Test
    void touchSeenRespectsTouchMinMinutes() {
        batch.markConnected(Map.of(MAC, "10.0.0.9"));
        // Set last_seen_at to 2 minutes ago (within default 5 minutes)
        jdbc.update("UPDATE endpoint SET last_seen_at = now() - interval '2 minutes' WHERE mac_address = ?", MAC);
        java.time.Instant before = jdbc.queryForObject(
                "SELECT last_seen_at FROM endpoint WHERE mac_address = ?", java.time.Instant.class, MAC);

        batch.touchSeen(List.of(MAC));
        java.time.Instant after = jdbc.queryForObject(
                "SELECT last_seen_at FROM endpoint WHERE mac_address = ?", java.time.Instant.class, MAC);
        assertEquals(before, after, "Should not update last_seen_at when newer than touch-min-minutes");

        // Set last_seen_at to 6 minutes ago (older than default 5 minutes)
        jdbc.update("UPDATE endpoint SET last_seen_at = now() - interval '6 minutes' WHERE mac_address = ?", MAC);
        batch.touchSeen(List.of(MAC));
        java.time.Instant touched = jdbc.queryForObject(
                "SELECT last_seen_at FROM endpoint WHERE mac_address = ?", java.time.Instant.class, MAC);
        org.junit.jupiter.api.Assertions.assertTrue(touched.isAfter(before),
                "Should update last_seen_at when older than touch-min-minutes");
    }
}