package com.endpointposture.job.recovery;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.job.JobStatus;
import com.endpointposture.job.JobType;
import com.endpointposture.job.PostureJob;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class JobRecoveryServiceIntegrationTest {

    private static final String MAC_PREFIX = "F2:5A:00:00:00:";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("endpoint_posture_recovery_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "testcontainers-only-secret-at-least-256-bits-long-value");
        registry.add("app.seed-admin.password", () -> "test-admin-password");
        registry.add("app.posture.api-key", () -> "test-agent-key");
        registry.add("app.jobs.workers.enabled", () -> "false");
        registry.add("app.jobs.recheck.enabled", () -> "false");
        registry.add("app.retention.enabled", () -> "false");
    }

    @Autowired
    private JobRecoveryService recoveryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID endpointId;
    private UUID jobId;

    @AfterEach
    void cleanUp() {
        if (jobId != null) {
            jdbc.update("DELETE FROM job_recovery_event WHERE job_id = ?", jobId);
            jdbc.update("DELETE FROM posture_job WHERE id = ?", jobId);
        }
        if (endpointId != null) {
            jdbc.update("DELETE FROM assessment WHERE endpoint_id = ?", endpointId);
            jdbc.update("DELETE FROM endpoint WHERE id = ?", endpointId);
        }
        dropFailureTrigger("assessment", "test_reject_assessment_insert");
        dropFailureTrigger("job_recovery_event", "test_reject_recovery_event_insert");
    }

    @Test
    void successfulRecoveryCommitsJobTransitionFailureEvidenceAndAuditTogether() {
        PostureJob candidate = runningPostureJob();

        assertTrue(recoveryService.recoverJob(candidate, RecoveryType.PROCESS_TIMEOUT, "worker process disappeared"));

        assertEquals("QUEUED", jobStatus());
        assertEquals(1, count("SELECT count(*) FROM assessment WHERE endpoint_id = ?", endpointId));
        assertEquals(1, count("SELECT count(*) FROM job_recovery_event WHERE job_id = ?", jobId));
        assertEquals("F2:5A:00:00:00:01", jdbc.queryForObject(
                "SELECT endpoint_mac_address FROM job_recovery_event WHERE job_id = ?", String.class, jobId));
    }

    @Test
    void evidenceInsertFailureRollsBackJobTransitionAndAudit() {
        PostureJob candidate = runningPostureJob();
        installFailureTrigger("assessment", "test_reject_assessment_insert");

        assertThrows(RuntimeException.class,
                () -> recoveryService.recoverJob(candidate, RecoveryType.PROCESS_TIMEOUT, "simulated evidence failure"));

        assertEquals("RUNNING", jobStatus());
        assertEquals(0, count("SELECT count(*) FROM assessment WHERE endpoint_id = ?", endpointId));
        assertEquals(0, count("SELECT count(*) FROM job_recovery_event WHERE job_id = ?", jobId));
    }

    @Test
    void auditInsertFailureRollsBackJobTransitionAndPreviouslyWrittenEvidence() {
        PostureJob candidate = runningPostureJob();
        installFailureTrigger("job_recovery_event", "test_reject_recovery_event_insert");

        assertThrows(RuntimeException.class,
                () -> recoveryService.recoverJob(candidate, RecoveryType.PROCESS_TIMEOUT, "simulated audit failure"));

        assertEquals("RUNNING", jobStatus());
        assertEquals(0, count("SELECT count(*) FROM assessment WHERE endpoint_id = ?", endpointId));
        assertEquals(0, count("SELECT count(*) FROM job_recovery_event WHERE job_id = ?", jobId));
    }

    @Test
    void supersededAttemptDoesNotWriteEvidenceOrAudit() {
        PostureJob candidate = runningPostureJob();
        jdbc.update("UPDATE posture_job SET attempt_count = 2 WHERE id = ?", jobId);

        assertFalse(recoveryService.recoverJob(candidate, RecoveryType.PROCESS_TIMEOUT, "stale attempt"));

        assertEquals("RUNNING", jobStatus());
        assertEquals(0, count("SELECT count(*) FROM assessment WHERE endpoint_id = ?", endpointId));
        assertEquals(0, count("SELECT count(*) FROM job_recovery_event WHERE job_id = ?", jobId));
    }

    private PostureJob runningPostureJob() {
        endpointId = jdbc.queryForObject(
                "INSERT INTO endpoint (mac_address, connected) VALUES (?, true) RETURNING id",
                UUID.class, MAC_PREFIX + "01");

        jobId = jdbc.queryForObject("""
                INSERT INTO posture_job
                    (endpoint_id, job_type, status, priority, attempt_count, max_attempts,
                     shard, claimed_by, started_at, lease_expires_at)
                VALUES (?, 'POSTURE_CHECK', 'RUNNING', 0, 1, 3, 'default',
                        'local-integration-test', ?, NULL)
                RETURNING id
                """, UUID.class, endpointId, Instant.now().minusSeconds(600));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> entityManager.createQuery(
                        "SELECT j FROM PostureJob j JOIN FETCH j.endpoint WHERE j.id = :id", PostureJob.class)
                .setParameter("id", jobId)
                .getSingleResult());
    }

    private String jobStatus() {
        return jdbc.queryForObject("SELECT status FROM posture_job WHERE id = ?", String.class, jobId);
    }

    private int count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private void installFailureTrigger(String table, String function) {
        jdbc.execute("CREATE OR REPLACE FUNCTION " + function
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'intentional integration-test failure'; END; $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION " + function + "()");
    }

    private void dropFailureTrigger(String table, String function) {
        jdbc.execute("DROP TRIGGER IF EXISTS " + function + " ON " + table);
        jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
    }
}
