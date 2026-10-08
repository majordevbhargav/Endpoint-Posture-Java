package com.endpointposture.job;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class RecheckSqlTest {

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
        r.add("app.retention.enabled", () -> "false");
    }

    @Autowired RecheckScheduler scheduler;
    @Autowired JdbcTemplate jdbc;
    @Autowired PostureJobRepository jobRepository;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM posture_job");
        jdbc.update("DELETE FROM assessment");
        jdbc.update("DELETE FROM endpoint");
    }

    private UUID endpoint(String mac, String ip, boolean connected) {
        return jdbc.queryForObject(
                "INSERT INTO endpoint (mac_address, ip_address, connected) VALUES (?,?,?) RETURNING id",
                UUID.class, mac, ip, connected);
    }

    private long jobs(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM posture_job WHERE job_type = ? AND status = 'QUEUED'",
                Long.class, type);
    }

    @Test
    void aConnectedDeviceGetsOnePostureJobAndASecondSweepAddsNone() {
        endpoint("DD:DD:DD:DD:DD:01", "10.0.0.1", true);
        scheduler.sweep();
        scheduler.sweep();
        assertEquals(1, jobs("POSTURE_CHECK"));
    }

    @Test
    void disconnectedAndTargetlessDevicesAreSkipped() {
        endpoint("DD:DD:DD:DD:DD:02", "10.0.0.2", false);
        endpoint("DD:DD:DD:DD:DD:03", null, true);
        scheduler.sweep();
        assertEquals(0, jobs("POSTURE_CHECK"));
    }

    @Test
    void hardwareWaitsForANonErrorAssessment() {
        UUID id = endpoint("DD:DD:DD:DD:DD:04", "10.0.0.4", true);
        scheduler.sweep();
        assertEquals(0, jobs("HARDWARE_CHECK"));

        jdbc.update("INSERT INTO assessment (endpoint_id, status, started_at) VALUES (?, 'COMPLIANT', now())", id);
        scheduler.sweep();
        assertEquals(1, jobs("HARDWARE_CHECK"));
    }

    @Test
    void aRecentlyCompletedJobSuppressesTheRecheck() {
        UUID id = endpoint("DD:DD:DD:DD:DD:05", "10.0.0.5", true);
        jdbc.update("INSERT INTO posture_job (endpoint_id, job_type, status, completed_at) "
                + "VALUES (?, 'POSTURE_CHECK', 'COMPLETE', now())", id);
        scheduler.sweep();
        assertEquals(0, jobs("POSTURE_CHECK"));
    }

    @Test
    void aRecentFailureIsBackedOffButAnOldOneIsNot() {
        UUID recent = endpoint("DD:DD:DD:DD:DD:06", "10.0.0.6", true);
        UUID old = endpoint("DD:DD:DD:DD:DD:07", "10.0.0.7", true);
        jdbc.update("INSERT INTO posture_job (endpoint_id, job_type, status, completed_at) "
                + "VALUES (?, 'POSTURE_CHECK', 'FAILED', now())", recent);
        jdbc.update("INSERT INTO posture_job (endpoint_id, job_type, status, completed_at, created_at) "
                + "VALUES (?, 'POSTURE_CHECK', 'FAILED', now() - interval '10 hours', now() - interval '10 hours')", old);

        scheduler.sweep();

        assertEquals(1, jobs("POSTURE_CHECK"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM posture_job WHERE endpoint_id = ? AND status = 'QUEUED'", Long.class, old));
    }

    @Test
    void maxPerSweepLimitIsRespectedAcrossTwoSweepsPickingOldestCheckedFirst() {
        RecheckScheduler limitedScheduler = new RecheckScheduler(jdbc, 4, 24, 6, 2);

        UUID e1 = endpoint("DD:DD:DD:DD:DD:11", "10.0.0.11", true);
        UUID e2 = endpoint("DD:DD:DD:DD:DD:12", "10.0.0.12", true);
        UUID e3 = endpoint("DD:DD:DD:DD:DD:13", "10.0.0.13", true);

        // e1 was checked 10 hours ago; e2 was never checked (nulls first); e3 was checked 5 hours ago
        jdbc.update("INSERT INTO posture_job (endpoint_id, job_type, status, completed_at) "
                + "VALUES (?, 'POSTURE_CHECK', 'COMPLETE', now() - interval '10 hours')", e1);
        jdbc.update("INSERT INTO posture_job (endpoint_id, job_type, status, completed_at) "
                + "VALUES (?, 'POSTURE_CHECK', 'COMPLETE', now() - interval '5 hours')", e3);

        // First sweep with limit 2: should queue e2 (null) and e1 (10 hours ago), but not e3
        limitedScheduler.sweep();
        assertEquals(2, jobs("POSTURE_CHECK"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM posture_job WHERE endpoint_id = ? AND status = 'QUEUED'", Long.class, e2));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM posture_job WHERE endpoint_id = ? AND status = 'QUEUED'", Long.class, e1));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM posture_job WHERE endpoint_id = ? AND status = 'QUEUED'", Long.class, e3));

        // Second sweep: e1 and e2 already QUEUED, now e3 is queued
        limitedScheduler.sweep();
        assertEquals(3, jobs("POSTURE_CHECK"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM posture_job WHERE endpoint_id = ? AND status = 'QUEUED'", Long.class, e3));
    }

    @Test
    void hardwareJobsGetLowerPriorityThanPostureAndClaimOrderingHandlesNegatives() {
        UUID id = endpoint("DD:DD:DD:DD:DD:20", "10.0.0.20", true);
        jdbc.update("INSERT INTO assessment (endpoint_id, status, started_at) VALUES (?, 'COMPLIANT', now())", id);

        scheduler.sweep();

        assertEquals(1, jobs("POSTURE_CHECK"));
        assertEquals(1, jobs("HARDWARE_CHECK"));

        Integer posturePriority = jdbc.queryForObject(
                "SELECT priority FROM posture_job WHERE endpoint_id = ? AND job_type = 'POSTURE_CHECK'",
                Integer.class, id);
        Integer hardwarePriority = jdbc.queryForObject(
                "SELECT priority FROM posture_job WHERE endpoint_id = ? AND job_type = 'HARDWARE_CHECK'",
                Integer.class, id);

        assertEquals(0, posturePriority);
        assertEquals(-5, hardwarePriority);

        // findNextClaimable must claim posture (priority 0) first, then hardware (priority -5)
        PostureJob first = jobRepository.findNextClaimable().orElseThrow();
        assertEquals(JobType.POSTURE_CHECK, first.getJobType());
        assertEquals(0, first.getPriority());

        // Mark first RUNNING so the second can be claimed
        first.setStatus(JobStatus.RUNNING);
        jobRepository.save(first);

        PostureJob second = jobRepository.findNextClaimable().orElseThrow();
        assertEquals(JobType.HARDWARE_CHECK, second.getJobType());
        assertEquals(-5, second.getPriority());
    }
}