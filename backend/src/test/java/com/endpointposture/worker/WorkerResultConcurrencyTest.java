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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
@AutoConfigureMockMvc
class WorkerResultConcurrencyTest {
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
    void concurrentIdenticalSubmissionsCreateOneReceiptAndOneEvidenceRow() throws Exception {
        WorkerRegistryService.Enrolled worker = registry.enroll(
                registry.createEnrollmentToken("worker-concurrent", "concurrent", 10, "test").token());
        String mac = mac();
        UUID endpointId = jdbc.queryForObject(
                "INSERT INTO endpoint(mac_address, connected) VALUES (?, true) RETURNING id", UUID.class, mac);
        UUID jobId = jdbc.queryForObject(
                "INSERT INTO posture_job(endpoint_id, job_type, shard) VALUES (?, 'POSTURE_CHECK', 'concurrent') RETURNING id",
                UUID.class, endpointId);
        int attempt = jobService.claimNextJobInShard(worker.workerId().toString(), "concurrent", Duration.ofMinutes(5))
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
        byte[] json = mapper.writeValueAsBytes(body);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> one = pool.submit(() -> send(jobId, worker.apiKey(), json, ready, go));
            Future<MvcResult> two = pool.submit(() -> send(jobId, worker.apiKey(), json, ready, go));
            ready.await();
            go.countDown();
            assertEquals(200, one.get().getResponse().getStatus());
            assertEquals(200, two.get().getResponse().getStatus());
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM worker_job_result_receipt WHERE job_id = ?", Integer.class, jobId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM assessment WHERE job_id = ?", Integer.class, jobId));
    }

    private MvcResult send(UUID id, String key, byte[] json, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mvc.perform(post("/api/v1/workers/jobs/{id}/result", id)
                .header(WorkerAuthFilter.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private String mac() {
        String h = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return "02:" + h.substring(0,2) + ":" + h.substring(2,4) + ":"
                + h.substring(4,6) + ":" + h.substring(6,8) + ":" + h.substring(8,10);
    }
}
