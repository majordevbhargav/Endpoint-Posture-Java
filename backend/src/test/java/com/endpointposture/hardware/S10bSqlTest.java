package com.endpointposture.hardware;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.dashboard.DashboardService;
import com.endpointposture.hardware.HardwareHealthService.RecommendationInput;
import com.endpointposture.hardware.dto.HardwareListItem;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.AssessmentStatus;
import com.endpointposture.posture.dto.CheckInput;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class S10bSqlTest {

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

    @Autowired HardwareHealthService hardware;
    @Autowired HardwareQueryService hwQuery;
    @Autowired AssessmentService assessments;
    @Autowired DashboardService dashboard;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM endpoint");
    }

    private UUID endpoint(String mac, String host, boolean connected) {
        return jdbc.queryForObject(
                "INSERT INTO endpoint (mac_address, hostname, ip_address, connected) VALUES (?,?,?,?) RETURNING id",
                UUID.class, mac, host, "10.0.0.1", connected);
    }

    @Test
    void hardwarePointersTrackSuccessThenFailureThenNeverCollectedThenNoReport() {
        UUID a = endpoint("CC:CC:CC:CC:CC:01", "A", true);
        UUID b = endpoint("CC:CC:CC:CC:CC:02", "B", true);
        endpoint("CC:CC:CC:CC:CC:03", "C", true);

        hardware.recordReport(a, null, "Dell", "Latitude", "SN1", "1.0", 80, 70, 90, null, 2, "UNKNOWN", null,
                Map.of(), Instant.now(), List.of(new RecommendationInput("HIGH", "Storage", "x")));

        HardwareListItem ok = hwQuery.page(0, 25, "Latitude", null).items().get(0);
        assertEquals("OK", ok.state());
        assertEquals(80, ok.overallScore());
        assertEquals(1, ok.recommendationCount());
        assertNull(ok.lastAttemptFailedAt());

        hardware.recordFailure(a, null, "boom");
        HardwareListItem afterFail = hwQuery.page(0, 25, "Latitude", null).items().get(0);
        assertEquals("OK", afterFail.state());              // still the last good scores
        assertEquals("boom", afterFail.lastAttemptError());
        assertEquals(1, hwQuery.page(0, 25, null, "LAST_FAILED").total());

        hardware.recordFailure(b, null, "never worked");
        assertEquals(1, hwQuery.page(0, 25, null, "FAILED").total());
        assertEquals(1, hwQuery.page(0, 25, null, "NO_REPORT").total());
        assertEquals(0, hwQuery.page(0, 25, "%", null).total(), "a literal % must not match everything");

        var s = hwQuery.summary();
        assertEquals(3, s.total());
        assertEquals(1, s.withReport());
        assertEquals(80, s.avgScore());
        assertEquals(1, s.lastAttemptFailed());
        assertEquals(1, s.neverCollected());
        assertEquals(1, s.noReport());
        assertEquals(1, s.recommendations());
    }

    @Test
    void postureBatchReturnsOnlyAssessedEndpointsWithTheirChecks() {
        UUID a = endpoint("CC:CC:CC:CC:CC:11", "A", true);
        UUID b = endpoint("CC:CC:CC:CC:CC:12", "B", true);
        Instant now = Instant.now();
        assessments.recordAssessment(a, null, now, now, AssessmentStatus.COMPLIANT, "d",
                List.of(new CheckInput("FIREWALL", AssessmentStatus.COMPLIANT, null)));

        var out = assessments.getLatestForEndpoints(List.of(a, b));

        assertEquals(1, out.size());
        assertEquals(a, out.get(0).endpointId());
        assertEquals(1, out.get(0).checks().size());
    }

    @Test
    void categoriesCountConnectedDevicesOnly() {
        UUID on = endpoint("CC:CC:CC:CC:CC:21", "A", true);
        UUID off = endpoint("CC:CC:CC:CC:CC:22", "B", false);
        Instant now = Instant.now();
        assessments.recordAssessment(on, null, now, now, AssessmentStatus.COMPLIANT, "d",
                List.of(new CheckInput("FIREWALL", AssessmentStatus.COMPLIANT, null)));
        assessments.recordAssessment(off, null, now, now, AssessmentStatus.NON_COMPLIANT, "d",
                List.of(new CheckInput("FIREWALL", AssessmentStatus.NON_COMPLIANT, null)));

        var cats = dashboard.categories();

        assertEquals(1, cats.size());
        assertEquals(1, cats.get(0).total());
        assertEquals(100.0, cats.get(0).passPercent());
    }
}