package com.endpointposture.dashboard;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.endpoint.EndpointQueryService;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.AssessmentStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class ReadPathSqlTest {

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
    @Autowired AssessmentService assessments;
    @Autowired DashboardService dashboard;
    @Autowired EndpointQueryService query;
    @Autowired TrendRollupScheduler rollup;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM compliance_daily");
        jdbc.update("DELETE FROM endpoint"); // cascades to assessments and checks
    }

    private UUID endpoint(String mac, String host, boolean connected) {
        return jdbc.queryForObject(
                "INSERT INTO endpoint (mac_address, hostname, ip_address, connected) VALUES (?,?,?,?) RETURNING id",
                UUID.class, mac, host, "10.0.0.1", connected);
    }

    private void assess(UUID id, AssessmentStatus s) {
        Instant now = Instant.now();
        assessments.recordAssessment(id, null, now, now, s, "d", List.of());
    }

    @Test
    void theNewestAssessmentDrivesTheSummaryAndOnlyConnectedDevicesAreCounted() {
        UUID a = endpoint("AA:AA:AA:AA:AA:01", "A", true);
        UUID b = endpoint("AA:AA:AA:AA:AA:02", "B", true);
        UUID off = endpoint("AA:AA:AA:AA:AA:03", "C", false);
        assess(a, AssessmentStatus.COMPLIANT);
        assess(a, AssessmentStatus.ERROR);          // newest wins
        assess(off, AssessmentStatus.COMPLIANT);    // not connected: excluded from posture buckets

        DashboardDtos.Summary s = dashboard.summary();

        assertEquals(3, s.total());
        assertEquals(2, s.connected());
        assertEquals(1, s.error());
        assertEquals(0, s.compliant());
        assertEquals(1, s.unassessed());            // b never assessed
        assertEquals(0, s.stale());
    }

    @Test
    void todayIsLiveAndTheRollupSnapshotMatchesIt() {
        UUID a = endpoint("AA:AA:AA:AA:AA:11", "A", true);
        UUID b = endpoint("AA:AA:AA:AA:AA:12", "B", true);
        assess(a, AssessmentStatus.COMPLIANT);
        assess(b, AssessmentStatus.NON_COMPLIANT);

        DashboardDtos.TrendPoint live = dashboard.trend(1).get(0);
        assertEquals(2, live.assessed());
        assertEquals(50.0, live.compliantPercent());

        rollup.snapshot();
        assertEquals(2, jdbc.queryForObject("SELECT assessed FROM compliance_daily", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT compliant FROM compliance_daily", Integer.class));
    }

    @Test
    void pagingSearchAndStatusFiltersWorkAndWildcardsAreEscaped() {
        for (int i = 0; i < 30; i++) {
            UUID id = endpoint(String.format("BB:BB:BB:BB:BB:%02X", i), "HOST_" + i, i % 2 == 0);
            if (i < 10) assess(id, AssessmentStatus.NON_COMPLIANT);
        }
        assertEquals(30, query.page(0, 10, null, null, null).total());
        assertEquals(10, query.page(0, 10, null, null, null).items().size());
        assertEquals(10, query.page(2, 10, null, null, null).items().size());
        assertEquals(15, query.page(0, 50, null, true, null).total());
        assertEquals(10, query.page(0, 50, null, null, "NON_COMPLIANT").total());
        assertEquals(20, query.page(0, 50, null, null, "UNASSESSED").total());
        assertEquals(1, query.page(0, 50, "host_7", null, null).total());
        assertEquals(0, query.page(0, 50, "%", null, null).total(), "a literal % must not match everything");
    }
}