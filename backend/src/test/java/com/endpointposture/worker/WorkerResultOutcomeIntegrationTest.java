package com.endpointposture.worker;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.job.JobService;
import com.endpointposture.job.JobType;
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
class WorkerResultOutcomeIntegrationTest {
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
    void acceptsAllFourJobTypesUsingTheirExistingEvidencePipelines() throws Exception {
        WorkerRegistryService.Enrolled worker = enroll("worker-all-types", "all-types");
        for (JobType type : JobType.values()) {
            UUID endpointId = endpoint();
            UUID jobId = job(endpointId, "all-types", type);
            int attempt = jobService.claimNextJobInShard(
                    worker.workerId().toString(), "all-types", Duration.ofMinutes(5))
                    .orElseThrow().getAttemptCount();

            ObjectNode body = envelope(attempt, type, "SUCCESS", payloadFor(type));
            MvcResult result = submit(jobId, worker.apiKey(), body);
            assertEquals(200, result.getResponse().getStatus(), type.name());
            assertEquals("COMPLETE", jdbc.queryForObject(
                    "SELECT status FROM posture_job WHERE id = ?", String.class, jobId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM worker_job_result_receipt WHERE job_id = ?", Integer.class, jobId));
        }

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM assessment", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM hardware_health", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM endpoint_diagnostic", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM endpoint_security_indicator", Integer.class));
    }

    @Test
    void partialResultStoresEvidenceAndCompletesWithoutChangingJobStatusEnum() throws Exception {
        WorkerRegistryService.Enrolled worker = enroll("worker-partial", "partial");
        UUID endpointId = endpoint();
        UUID jobId = job(endpointId, "partial", JobType.POSTURE_CHECK);
        int attempt = jobService.claimNextJobInShard(worker.workerId().toString(), "partial", Duration.ofMinutes(5))
                .orElseThrow().getAttemptCount();

        ObjectNode body = envelope(attempt, JobType.POSTURE_CHECK, "PARTIAL", payloadFor(JobType.POSTURE_CHECK));
        body.putArray("warnings").add("One optional check was unavailable");
        assertEquals(200, submit(jobId, worker.apiKey(), body).getResponse().getStatus());
        assertEquals("COMPLETE", jdbc.queryForObject(
                "SELECT status FROM posture_job WHERE id = ?", String.class, jobId));
        assertEquals("PARTIAL", jdbc.queryForObject(
                "SELECT collection_outcome FROM worker_job_result_receipt WHERE job_id = ?", String.class, jobId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM assessment WHERE job_id = ?", Integer.class, jobId));
    }

    @Test
    void failedResultWritesFailureEvidenceAndUsesExistingRetryPolicy() throws Exception {
        WorkerRegistryService.Enrolled worker = enroll("worker-failed", "failed");
        UUID endpointId = endpoint();
        UUID jobId = job(endpointId, "failed", JobType.POSTURE_CHECK);
        int attempt = jobService.claimNextJobInShard(worker.workerId().toString(), "failed", Duration.ofMinutes(5))
                .orElseThrow().getAttemptCount();

        ObjectNode body = envelope(attempt, JobType.POSTURE_CHECK, "FAILED", mapper.createObjectNode());
        body.putArray("errors").add("PowerShell process exited with code 1");
        assertEquals(200, submit(jobId, worker.apiKey(), body).getResponse().getStatus());
        assertEquals("QUEUED", jdbc.queryForObject(
                "SELECT status FROM posture_job WHERE id = ?", String.class, jobId));
        assertEquals("ERROR", jdbc.queryForObject(
                "SELECT status FROM assessment WHERE job_id = ?", String.class, jobId));
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT collection_outcome FROM worker_job_result_receipt WHERE job_id = ?", String.class, jobId));
        assertEquals(200, submit(jobId, worker.apiKey(), body).getResponse().getStatus(),
                "an identical retry after requeue returns its original receipt");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM assessment WHERE job_id = ?", Integer.class, jobId));
    }

    private ObjectNode payloadFor(JobType type) {
        ObjectNode p = mapper.createObjectNode();
        switch (type) {
            case POSTURE_CHECK -> {
                p.put("status", "COMPLIANT");
                ObjectNode check = mapper.createObjectNode();
                check.put("checkType", "FIREWALL");
                check.put("status", "COMPLIANT");
                check.set("details", mapper.createObjectNode());
                p.putArray("checks").add(check);
            }
            case HARDWARE_CHECK -> {
                ObjectNode cpuMemory = p.putObject("cpuMemory");
                cpuMemory.putObject("cpu").put("LoadPercentage", 20);
                cpuMemory.putObject("memory").put("UsedPercent", 35);
                p.putObject("storage").putArray("physical_disks")
                        .addObject().put("HealthStatus", "Healthy");
                ObjectNode endpoint = p.putObject("endpoint");
                endpoint.put("manufacturer", "Test");
                endpoint.put("model", "Model");
                endpoint.put("serialNumber", "SERIAL-" + UUID.randomUUID());
                p.putArray("proactiveRecommendations");
            }
            case DIAGNOSTIC_CHECK -> {
                p.put("status", "OK");
                p.putObject("gateway").put("reachable", true);
            }
            case SECURITY_CHECK -> {
                p.put("status", "OK");
                ObjectNode sample = p.putArray("samples").addObject();
                sample.put("index", 1);
                sample.put("offsetSec", 0);
                sample.putArray("connections");
            }
        }
        return p;
    }

    private ObjectNode envelope(int attempt, JobType type, String outcome, ObjectNode payload) {
        ObjectNode root = mapper.createObjectNode();
        root.put("attempt", attempt);
        root.put("schemaVersion", 1);
        root.put("jobType", type.name());
        root.put("collectionOutcome", outcome);
        root.set("reportPayload", payload);
        root.putArray("errors");
        root.putArray("warnings");
        root.put("processExitCode", outcome.equals("FAILED") ? 1 : 0);
        return root;
    }

    private MvcResult submit(UUID id, String key, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/v1/workers/jobs/{id}/result", id)
                .header(WorkerAuthFilter.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body))).andReturn();
    }

    private UUID endpoint() {
        String h = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String mac = "02:" + h.substring(0,2) + ":" + h.substring(2,4) + ":"
                + h.substring(4,6) + ":" + h.substring(6,8) + ":" + h.substring(8,10);
        return jdbc.queryForObject(
                "INSERT INTO endpoint(mac_address, connected) VALUES (?, true) RETURNING id", UUID.class, mac);
    }

    private UUID job(UUID endpointId, String shard, JobType type) {
        return jdbc.queryForObject(
                "INSERT INTO posture_job(endpoint_id, job_type, shard) VALUES (?, ?, ?) RETURNING id",
                UUID.class, endpointId, type.name(), shard);
    }

    private WorkerRegistryService.Enrolled enroll(String name, String shard) {
        var token = registry.createEnrollmentToken(name, shard, 10, "slice2-test");
        return registry.enroll(token.token());
    }
}
