package com.endpointposture.retention;

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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class EvidenceRetentionServiceTest {

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

    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM endpoint"); // cascades to every evidence table
    }

    private UUID endpoint(String mac) {
        return jdbc.queryForObject("INSERT INTO endpoint (mac_address) VALUES (?) RETURNING id", UUID.class, mac);
    }

    private UUID assessment(UUID endpointId, String status, int daysAgo) {
        return jdbc.queryForObject("""
                INSERT INTO assessment (endpoint_id, status, started_at, created_at)
                VALUES (?, ?, now(), now() - (? * interval '1 day')) RETURNING id
                """, UUID.class, endpointId, status, daysAgo);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void oldAssessmentsAreDeletedWithTheirChecksButTheCurrentPointerIsKept() {
        UUID ep = endpoint("EE:00:00:00:00:01");
        UUID old = assessment(ep, "COMPLIANT", 200);
        UUID pointer = assessment(ep, "COMPLIANT", 150);   // old, but it is the endpoint's latest
        UUID fresh = assessment(ep, "COMPLIANT", 1);
        jdbc.update("INSERT INTO check_result (assessment_id, check_type, status) VALUES (?, 'FIREWALL', 'COMPLIANT')", old);
        jdbc.update("UPDATE endpoint SET latest_assessment_id = ? WHERE id = ?", pointer, ep);

        Map<String, Long> deleted = new EvidenceRetentionService(jdbc, 90, 90, 100, 10).prune();

        assertEquals(1, deleted.get("assessment"));
        assertEquals(0, count("SELECT count(*) FROM check_result"), "checks cascade with their assessment");
        assertEquals(1, count("SELECT count(*) FROM assessment WHERE id = ?", pointer));
        assertEquals(1, count("SELECT count(*) FROM assessment WHERE id = ?", fresh));
    }

    @Test
    void batchLimitLoopsUntilEverythingOldIsGone() {
        UUID ep = endpoint("EE:00:00:00:00:02");
        for (int i = 0; i < 7; i++) assessment(ep, "COMPLIANT", 120 + i);

        Map<String, Long> deleted = new EvidenceRetentionService(jdbc, 90, 90, 2, 10).prune(); // batches of 2

        assertEquals(7, deleted.get("assessment"));
    }

    @Test
    void theBatchCapBoundsOneRunAndTheRestIsTakenNextTime() {
        UUID ep = endpoint("EE:00:00:00:00:03");
        for (int i = 0; i < 7; i++) assessment(ep, "COMPLIANT", 120 + i);
        EvidenceRetentionService svc = new EvidenceRetentionService(jdbc, 90, 90, 2, 2); // 2 x 2 per run

        assertEquals(4, svc.prune().get("assessment"));
        assertEquals(3, svc.prune().get("assessment")); // remaining 3 (2 + 1)
    }

    @Test
    void newestDiagnosticOfAnEndpointIsKeptEvenWhenOlderThanTheWindow() {
        UUID ep = endpoint("EE:00:00:00:00:04");
        jdbc.update("INSERT INTO endpoint_diagnostic (endpoint_id, status, raw_report, collected_at) VALUES (?, 'OK', '{}', now() - interval '300 days')", ep);
        jdbc.update("INSERT INTO endpoint_diagnostic (endpoint_id, status, raw_report, collected_at) VALUES (?, 'OK', '{}', now() - interval '200 days')", ep);

        new EvidenceRetentionService(jdbc, 90, 90, 100, 10).prune();

        assertEquals(1, count("SELECT count(*) FROM endpoint_diagnostic WHERE endpoint_id = ?", ep));
    }

    @Test
    void theLatestEnforcementActionPerEndpointSurvivesSoTheStateStaysCorrect() {
        UUID ep = endpoint("EE:00:00:00:00:05");
        jdbc.update("INSERT INTO ise_action_audit (endpoint_id, action_type, succeeded, occurred_at) VALUES (?, 'SHARE_POSTURE', true, now() - interval '300 days')", ep);
        jdbc.update("INSERT INTO ise_action_audit (endpoint_id, action_type, succeeded, occurred_at) VALUES (?, 'RESTRICT', true, now() - interval '250 days')", ep);
        jdbc.update("INSERT INTO ise_action_audit (endpoint_id, action_type, succeeded, occurred_at) VALUES (?, 'CLEAR_RESTRICTION', true, now() - interval '240 days')", ep);
        jdbc.update("INSERT INTO ise_action_audit (endpoint_id, action_type, succeeded, occurred_at) VALUES (?, 'RESTRICT', true, now() - interval '200 days')", ep);

        new EvidenceRetentionService(jdbc, 90, 90, 100, 10).prune();

        assertEquals(1, count("SELECT count(*) FROM ise_action_audit WHERE endpoint_id = ?", ep));
        assertEquals("RESTRICT", jdbc.queryForObject("SELECT action_type FROM ise_action_audit WHERE endpoint_id = ?", String.class, ep));
    }

    @Test
    void hardwareRowsReferencedByTheEndpointPointersAreKeptAndRecommendationsCascade() {
        UUID ep = endpoint("EE:00:00:00:00:06");
        UUID gone = jdbc.queryForObject("INSERT INTO hardware_health (endpoint_id, raw_report, collected_at) VALUES (?, '{}', now() - interval '300 days') RETURNING id", UUID.class, ep);
        UUID kept = jdbc.queryForObject("INSERT INTO hardware_health (endpoint_id, raw_report, collected_at) VALUES (?, '{}', now() - interval '200 days') RETURNING id", UUID.class, ep);
        jdbc.update("INSERT INTO hardware_recommendation (hardware_health_id, priority, area, action) VALUES (?, 'LOW', 'x', 'y')", gone);
        jdbc.update("UPDATE endpoint SET good_hw_id = ?, latest_hw_id = ? WHERE id = ?", kept, kept, ep);

        new EvidenceRetentionService(jdbc, 90, 90, 100, 10).prune();

        assertEquals(0, count("SELECT count(*) FROM hardware_health WHERE id = ?", gone));
        assertEquals(1, count("SELECT count(*) FROM hardware_health WHERE id = ?", kept));
        assertEquals(0, count("SELECT count(*) FROM hardware_recommendation"));
    }

    @Test
    void aWindowBelowOneDayIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceRetentionService(jdbc, 0, 90, 100, 10));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceRetentionService(jdbc, 90, 0, 100, 10));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceRetentionService(jdbc, 90, 90, 0, 10));
    }
}