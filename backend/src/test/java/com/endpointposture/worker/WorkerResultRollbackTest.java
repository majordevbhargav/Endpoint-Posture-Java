package com.endpointposture.worker;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.job.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
@AutoConfigureMockMvc
class WorkerResultRollbackTest {
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
        r.add("app.workers.enrollment-rate-limit.capacity", () -> "1000");
        r.add("app.workers.enrollment-rate-limit.refill-tokens", () -> "1000");
        r.add("app.workers.enrollment-rate-limit.refill-duration-seconds", () -> "60");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WorkerRegistryService registry;
    @Autowired JobService jobService;

    @Test
    void evidenceAndLifecycleRollBackWhenEvidenceOrReceiptPersistenceFails() throws Exception {
        WorkerRegistryService.Enrolled worker = registry.enroll(
                registry.createEnrollmentToken("worker-rollback", "rollback", 10, "test").token());
        String h = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String mac = "02:" + h.substring(0,2) + ":" + h.substring(2,4) + ":"
                + h.substring(4,6) + ":" + h.substring(6,8) + ":" + h.substring(8,10);
        UUID endpointId = jdbc.queryForObject(
                "INSERT INTO endpoint(mac_address, connected) VALUES (?, true) RETURNING id", UUID.class, mac);
        UUID jobId = jdbc.queryForObject(
                "INSERT INTO posture_job(endpoint_id, job_type, shard) VALUES (?, 'POSTURE_CHECK', 'rollback') RETURNING id",
                UUID.class, endpointId);
        int attempt = jobService.claimNextJobInShard(worker.workerId().toString(), "rollback", Duration.ofMinutes(5))
                .orElseThrow().getAttemptCount();

        ObjectNode body = mapper.createObjectNode();
        body.put("attempt", attempt);
        body.put("schemaVersion", 1);
        body.put("jobType", "POSTURE_CHECK");
        body.put("collectionOutcome", "SUCCESS");
        ObjectNode payload = body.putObject("reportPayload");
        payload.put("macAddress", mac);
        payload.put("status", "COMPLIANT");
        ObjectNode check = payload.putArray("checks").addObject();
        check.put("checkType", "FIREWALL");
        check.put("status", "COMPLIANT");
        check.set("details", mapper.createObjectNode());
        body.putArray("errors");
        body.putArray("warnings");
        body.put("processExitCode", 0);

        installRejectTrigger("assessment", "reject_assessment_insert");
        try {
            invokeExpectingFailure(jobId, worker.apiKey(), body, "reject_assessment_insert");
        } finally {
            dropRejectTrigger("assessment", "reject_assessment_insert");
        }
        assertRolledBack(jobId);

        installRejectTrigger("worker_job_result_receipt", "reject_receipt_insert");
        try {
            invokeExpectingFailure(jobId, worker.apiKey(), body, "reject_receipt_insert");
        } finally {
            dropRejectTrigger("worker_job_result_receipt", "reject_receipt_insert");
        }
        assertRolledBack(jobId);
    }

    private void invokeExpectingFailure(UUID id, String key, ObjectNode body, String triggerFunction)
            throws Exception {
        MvcResult result = null;
        Exception propagated = null;
        try {
            result = mvc.perform(post("/api/v1/workers/jobs/{id}/result", id)
                    .header(WorkerAuthFilter.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(body))).andReturn();
        } catch (Exception failure) {
            propagated = failure;
        }

        if (result != null) {
            int status = result.getResponse().getStatus();
            assertTrue(status >= 500 && status < 600,
                    "Expected a server-side persistence failure, but received HTTP " + status
                            + " with body: " + result.getResponse().getContentAsString());
        } else {
            assertNotNull(propagated, "Expected the persistence failure to be returned or propagated");
            assertTrue(hasInjectedPersistenceFailure(propagated, triggerFunction),
                    "Expected the injected PostgreSQL trigger failure for " + triggerFunction
                            + " in the exception cause chain, but got: " + propagated);
        }

        assertTrue(triggerWasInvoked(triggerFunction),
                "The injected trigger " + triggerFunction + " must demonstrably execute");
    }

    private boolean hasInjectedPersistenceFailure(Throwable failure, String triggerFunction) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException
                    && cause.getMessage() != null
                    && cause.getMessage().contains("injected persistence failure: " + triggerFunction)) {
                return true;
            }
        }
        return false;
    }

    private boolean triggerWasInvoked(String triggerFunction) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_called FROM " + triggerFunction + "_invocations",
                Boolean.class));
    }

    private void assertRolledBack(UUID jobId) {
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT status FROM posture_job WHERE id = ?", String.class, jobId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM assessment WHERE job_id = ?", Integer.class, jobId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM worker_job_result_receipt WHERE job_id = ?", Integer.class, jobId));
    }

    private void installRejectTrigger(String table, String function) {
        jdbc.execute("CREATE SEQUENCE " + function + "_invocations");
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $trigger$ "
                + "BEGIN PERFORM nextval('" + function + "_invocations'); "
                + "RAISE EXCEPTION 'injected persistence failure: " + function + "'; END $trigger$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION " + function + "()");
    }

    private void dropRejectTrigger(String table, String function) {
        jdbc.execute("DROP TRIGGER IF EXISTS " + function + " ON " + table);
        jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
        jdbc.execute("DROP SEQUENCE IF EXISTS " + function + "_invocations");
    }
}
