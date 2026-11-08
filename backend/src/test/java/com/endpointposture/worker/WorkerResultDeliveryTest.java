package com.endpointposture.worker;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.job.JobService;
import com.fasterxml.jackson.databind.JsonNode;
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
class WorkerResultDeliveryTest {
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
    void acceptsTrustedEndpointEvidenceAndReturnsOriginalReceiptForReorderedDuplicate() throws Exception {
        UUID endpointId = endpoint(mac());
        UUID spoofedEndpointId = endpoint(mac());
        UUID jobId = job(endpointId, "site-result");
        WorkerRegistryService.Enrolled worker = enroll("worker-result", "site-result");
        var claimed = jobService.claimNextJobInShard(worker.workerId().toString(), "site-result", Duration.ofMinutes(5));
        assertTrue(claimed.isPresent());
        int attempt = claimed.orElseThrow().getAttemptCount();

        ObjectNode first = request(attempt, payload(macFor(spoofedEndpointId)));
        MvcResult accepted = submit(jobId, worker.apiKey(), first);
        assertEquals(200, accepted.getResponse().getStatus());
        JsonNode ack = mapper.readTree(accepted.getResponse().getContentAsString());
        assertTrue(ack.get("accepted").asBoolean());
        assertEquals(jobId.toString(), ack.get("jobId").asText());
        assertEquals(attempt, ack.get("attempt").asInt());

        ObjectNode reordered = request(attempt, payload(macFor(spoofedEndpointId)));
        // Rebuild payload with a different object-key insertion order.
        ObjectNode reversed = mapper.createObjectNode();
        reversed.put("hostname", "reported-host");
        reversed.put("macAddress", macFor(spoofedEndpointId));
        reversed.put("status", "COMPLIANT");
        reversed.set("checks", reordered.get("reportPayload").get("checks"));
        reordered.set("reportPayload", reversed);

        MvcResult duplicate = submit(jobId, worker.apiKey(), reordered);
        assertEquals(200, duplicate.getResponse().getStatus());
        JsonNode duplicateAck = mapper.readTree(duplicate.getResponse().getContentAsString());
        assertEquals(ack.get("receiptId").asText(), duplicateAck.get("receiptId").asText());
        assertEquals(ack.get("acceptedAt").asText(), duplicateAck.get("acceptedAt").asText());

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM worker_job_result_receipt WHERE job_id = ?", Integer.class, jobId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM assessment WHERE job_id = ?", Integer.class, jobId));
        assertEquals(endpointId, jdbc.queryForObject(
                "SELECT endpoint_id FROM assessment WHERE job_id = ?", UUID.class, jobId));
        assertEquals("COMPLETE", jdbc.queryForObject(
                "SELECT status FROM posture_job WHERE id = ?", String.class, jobId));
    }

    @Test
    void rejectsMissingInvalidAndDifferentWorkerCredentials() throws Exception {
        UUID endpointId = endpoint(mac());
        UUID jobId = job(endpointId, "site-owner");
        WorkerRegistryService.Enrolled owner = enroll("worker-owner", "site-owner");
        WorkerRegistryService.Enrolled other = enroll("worker-other", "different-site");
        var claimed = jobService.claimNextJobInShard(owner.workerId().toString(), "site-owner", Duration.ofMinutes(5));
        int attempt = claimed.orElseThrow().getAttemptCount();
        ObjectNode body = request(attempt, payload(mac()));

        int missing = mvc.perform(post("/api/v1/workers/jobs/{id}/result", jobId)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andReturn().getResponse().getStatus();
        assertTrue(missing == 401 || missing == 403);

        assertEquals(401, submit(jobId, WorkerCredentials.newKey(), body).getResponse().getStatus());
        assertEquals(409, submit(jobId, other.apiKey(), body).getResponse().getStatus());
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT status FROM posture_job WHERE id = ?", String.class, jobId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM worker_job_result_receipt WHERE job_id = ?", Integer.class, jobId));
    }

    @Test
    void rejectsExpiredLeaseAndUnsupportedSchemaBeforeEvidenceWrites() throws Exception {
        UUID endpointId = endpoint(mac());
        UUID jobId = job(endpointId, "site-expiry");
        WorkerRegistryService.Enrolled worker = enroll("worker-expiry", "site-expiry");
        var claimed = jobService.claimNextJobInShard(worker.workerId().toString(), "site-expiry", Duration.ofMinutes(5));
        int attempt = claimed.orElseThrow().getAttemptCount();
        jdbc.update("UPDATE posture_job SET lease_expires_at = now() - interval '1 second' WHERE id = ?", jobId);

        assertEquals(409, submit(jobId, worker.apiKey(), request(attempt, payload(mac())))
                .getResponse().getStatus());
        ObjectNode unsupported = request(attempt, payload(mac()));
        unsupported.put("schemaVersion", 99);
        assertEquals(400, submit(jobId, worker.apiKey(), unsupported).getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM worker_job_result_receipt WHERE job_id = ?", Integer.class, jobId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM assessment WHERE job_id = ?", Integer.class, jobId));
    }

    private MvcResult submit(UUID jobId, String key, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/v1/workers/jobs/{id}/result", jobId)
                .header(WorkerAuthFilter.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body))).andReturn();
    }

    private ObjectNode request(int attempt, ObjectNode payload) {
        ObjectNode root = mapper.createObjectNode();
        root.put("attempt", attempt);
        root.put("schemaVersion", 1);
        root.put("jobType", "POSTURE_CHECK");
        root.put("collectionOutcome", "SUCCESS");
        root.set("reportPayload", payload);
        root.putArray("errors");
        root.putArray("warnings");
        root.put("processExitCode", 0);
        return root;
    }

    private ObjectNode payload(String reportedMac) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("macAddress", reportedMac);
        payload.put("hostname", "reported-host");
        payload.put("status", "COMPLIANT");
        ObjectNode check = mapper.createObjectNode();
        check.put("checkType", "FIREWALL");
        check.put("status", "COMPLIANT");
        check.set("details", mapper.createObjectNode());
        payload.putArray("checks").add(check);
        return payload;
    }

    private UUID endpoint(String mac) {
        return jdbc.queryForObject(
                "INSERT INTO endpoint(mac_address, connected) VALUES (?, true) RETURNING id",
                UUID.class, mac);
    }

    private UUID job(UUID endpointId, String shard) {
        return jdbc.queryForObject(
                "INSERT INTO posture_job(endpoint_id, job_type, shard) VALUES (?, 'POSTURE_CHECK', ?) RETURNING id",
                UUID.class, endpointId, shard);
    }

    private WorkerRegistryService.Enrolled enroll(String name, String shard) {
        var token = registry.createEnrollmentToken(name, shard, 10, "slice2-test");
        return registry.enroll(token.token());
    }

    private String mac() {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return "02:" + hex.substring(0,2) + ":" + hex.substring(2,4) + ":"
                + hex.substring(4,6) + ":" + hex.substring(6,8) + ":" + hex.substring(8,10);
    }

    private String macFor(UUID ignored) {
        // The caller only needs a syntactically plausible, deliberately unrelated reported MAC.
        return "02:AA:BB:CC:DD:EE";
    }
}
