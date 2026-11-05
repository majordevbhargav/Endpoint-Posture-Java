package com.endpointposture.worker;

import com.endpointposture.EndpointPostureApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
@AutoConfigureMockMvc
class WorkerAuthenticationFlowTest {

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

    private static final String ADMIN_TOKENS = "/api/v1/admin/workers/enrollment-tokens";
    private static final String ENROLL = "/api/v1/workers/enroll";
    private static final String CLAIM = "/api/v1/workers/jobs/claim";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    WorkerRegistryService registry;
    @Autowired
    ObjectMapper json;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM assessment");
        jdbc.update("DELETE FROM posture_job");
        jdbc.update("DELETE FROM endpoint");
        jdbc.update("DELETE FROM worker_enrollment_token");
        jdbc.update("DELETE FROM worker_registry");
        jdbc.update("DELETE FROM worker_auth_event");
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String adminCreatesToken(String name, String shard) throws Exception {
        var res = mvc.perform(MockMvcRequestBuilders.post(ADMIN_TOKENS)
                .with(SecurityMockMvcRequestPostProcessors.user("root").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerName\":\"" + name + "\",\"shard\":\"" + shard + "\",\"ttlMinutes\":10}"))
                .andReturn();
        assertEquals(201, res.getResponse().getStatus());
        return body(res).get("token").asText();
    }

    private int enrollStatus(String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(ENROLL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andReturn().getResponse().getStatus();
    }

    private JsonNode enrollOk(String token) throws Exception {
        var res = mvc.perform(MockMvcRequestBuilders.post(ENROLL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andReturn();
        assertEquals(200, res.getResponse().getStatus());
        return body(res);
    }

    private int claimStatus(String key) throws Exception {
        var b = MockMvcRequestBuilders.post(CLAIM);
        if (key != null)
            b.header(WorkerAuthFilter.HEADER, key);
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.MvcResult claim(String key) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(CLAIM).header(WorkerAuthFilter.HEADER, key)).andReturn();
    }

    private int finish(String key, UUID jobId, String action, String jsonBody) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/workers/jobs/" + jobId + "/" + action)
                .header(WorkerAuthFilter.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON).content(jsonBody)).andReturn().getResponse().getStatus();
    }

    private UUID endpoint(String mac, String ip) {
        return jdbc.queryForObject(
                "INSERT INTO endpoint (mac_address, ip_address, connected) VALUES (?, ?, true) RETURNING id",
                UUID.class, mac, ip);
    }

    private UUID job(UUID endpointId, String shard) {
        return jdbc.queryForObject(
                "INSERT INTO posture_job (endpoint_id, job_type, shard) VALUES (?, 'POSTURE_CHECK', ?) RETURNING id",
                UUID.class, endpointId, shard);
    }

    private long events(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM worker_auth_event WHERE event_type = ?", Long.class, type);
    }

    private String jobStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM posture_job WHERE id = ?", String.class, id);
    }

    @Test
    void enrollAuthenticateClaimOwnShardOnlyAndCompleteWithTheFencingToken() throws Exception {
        UUID ep = endpoint("DD:00:00:00:01:01", "10.1.0.1");
        UUID jobA = job(ep, "site-a");
        UUID jobB = job(ep, "site-b");

        String token = adminCreatesToken("flow-a", "site-a");
        JsonNode enrolled = enrollOk(token);
        String key = enrolled.get("apiKey").asText();
        assertTrue(key.startsWith(WorkerCredentials.KEY_PREFIX));
        assertEquals("site-a", enrolled.get("shard").asText());
        assertEquals(401, enrollStatus(token));
        assertEquals(1, events("ENROLLED"));
        assertEquals(1, events("ENROLL_REJECTED"));
        assertEquals(1, events("TOKEN_CREATED"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM worker_registry WHERE key_hash = ?",
                Integer.class, key));

        int none = claimStatus(null);
        assertTrue(none == 401 || none == 403);
        assertEquals(401, claimStatus(WorkerCredentials.newKey()));
        assertEquals(401, claimStatus("not-a-key"));
        assertEquals(2, events("AUTH_FAILED"));

        var claimed = claim(key);
        assertEquals(200, claimed.getResponse().getStatus());
        JsonNode c = body(claimed);
        assertEquals(jobA.toString(), c.get("jobId").asText());
        assertEquals("POSTURE_CHECK", c.get("jobType").asText());
        int attempt = c.get("attempt").asInt();
        assertEquals(1, attempt);
        assertEquals(204, claim(key).getResponse().getStatus());
        assertEquals("QUEUED", jobStatus(jobB));
        assertEquals(enrolled.get("workerId").asText(),
                jdbc.queryForObject("SELECT claimed_by FROM posture_job WHERE id = ?", String.class, jobA));

        assertEquals(409, finish(key, jobA, "complete", "{\"attempt\":" + (attempt + 1) + "}"));
        assertEquals("RUNNING", jobStatus(jobA));
        assertEquals(200, finish(key, jobA, "complete", "{\"attempt\":" + attempt + "}"));
        assertEquals("COMPLETE", jobStatus(jobA));
        assertEquals(409, finish(key, jobA, "complete", "{\"attempt\":" + attempt + "}"));
    }

    @Test
    void anotherWorkerCannotCompleteOrFailAJobItDoesNotOwn() throws Exception {
        UUID ep = endpoint("DD:00:00:00:02:01", "10.1.0.2");
        UUID jobId = job(ep, "site-a");
        String owner = enrollOk(adminCreatesToken("flow-owner", "site-a")).get("apiKey").asText();
        String thief = registry.enroll(registry.createEnrollmentToken("flow-thief", "site-a", 10, "root").token())
                .apiKey();

        assertEquals(200, claim(owner).getResponse().getStatus());
        assertEquals(409, finish(thief, jobId, "complete", "{\"attempt\":1}"));
        assertEquals(409, finish(thief, jobId, "fail", "{\"attempt\":1,\"reason\":\"nope\"}"));
        assertEquals("RUNNING", jobStatus(jobId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM assessment", Integer.class));
    }

    @Test
    void anAcceptedFailureRequeuesTheJobAndRecordsEvidence() throws Exception {
        UUID ep = endpoint("DD:00:00:00:03:01", "10.1.0.3");
        UUID jobId = job(ep, "site-a");
        String key = enrollOk(adminCreatesToken("flow-fail", "site-a")).get("apiKey").asText();

        assertEquals(200, claim(key).getResponse().getStatus());
        assertEquals(200, finish(key, jobId, "fail", "{\"attempt\":1,\"reason\":\"WinRM refused\"}"));
        assertEquals("QUEUED", jobStatus(jobId));
        assertNull(jdbc.queryForObject("SELECT claimed_by FROM posture_job WHERE id = ?", String.class, jobId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM assessment WHERE job_id = ? AND status = 'ERROR'",
                Integer.class, jobId));
    }

    @Test
    void anExpiredEnrollmentTokenIsRejected() throws Exception {
        String token = adminCreatesToken("flow-expired", "site-a");
        jdbc.update("UPDATE worker_enrollment_token SET expires_at = now() - interval '1 minute'");
        assertEquals(401, enrollStatus(token));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM worker_registry", Integer.class));
    }

    @Test
    void anExpiredLeaseCannotBeCompleted() throws Exception {
        UUID ep = endpoint("DD:00:00:00:04:01", "10.1.0.4");
        UUID jobId = job(ep, "site-a");
        String key = enrollOk(adminCreatesToken("flow-lease", "site-a")).get("apiKey").asText();

        assertEquals(200, claim(key).getResponse().getStatus());
        jdbc.update("UPDATE posture_job SET lease_expires_at = now() - interval '1 second' WHERE id = ?", jobId);
        assertEquals(409, finish(key, jobId, "complete", "{\"attempt\":1}"));
        assertEquals("RUNNING", jobStatus(jobId));
    }

    @Test
    void aRevokedKeyIsRejectedImmediatelyAndOpensNothingElse() throws Exception {
        UUID ep = endpoint("DD:00:00:00:05:01", "10.1.0.5");
        job(ep, "site-a");
        JsonNode enrolled = enrollOk(adminCreatesToken("flow-revoked", "site-a"));
        String key = enrolled.get("apiKey").asText();
        int dashboardBeforeRevoke = mvc.perform(MockMvcRequestBuilders.get("/api/v1/endpoints")
                .header(WorkerAuthFilter.HEADER, key)).andReturn().getResponse().getStatus();
        assertTrue(dashboardBeforeRevoke == 401 || dashboardBeforeRevoke == 403,
                "a valid worker key must not authorize dashboard routes");

        assertEquals(200, claim(key).getResponse().getStatus());
        int revoke = mvc.perform(MockMvcRequestBuilders
                .post("/api/v1/admin/workers/" + enrolled.get("workerId").asText() + "/revoke")
                .with(SecurityMockMvcRequestPostProcessors.user("root").roles("ADMIN")))
                .andReturn().getResponse().getStatus();
        assertEquals(204, revoke);
        assertEquals(401, claimStatus(key));
        assertEquals(1, events("WORKER_REVOKED"));
        assertEquals(1, events("AUTH_REVOKED"));

        int dashboard = mvc.perform(MockMvcRequestBuilders.get("/api/v1/endpoints")
                .header(WorkerAuthFilter.HEADER, key)).andReturn().getResponse().getStatus();
        assertTrue(dashboard == 401 || dashboard == 403);
    }

    @Test
    void aWorkerKeyCannotUseTheAdminWorkerApi() throws Exception {
        String key = enrollOk(adminCreatesToken("flow-noadmin", "site-a")).get("apiKey").asText();
        int s = mvc.perform(MockMvcRequestBuilders.post(ADMIN_TOKENS).header(WorkerAuthFilter.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerName\":\"x-worker\",\"shard\":\"site-a\"}")).andReturn().getResponse().getStatus();
        assertTrue(s == 401 || s == 403);
    }

    private org.springframework.test.web.servlet.MvcResult heartbeat(String header, String key) throws Exception {
        var b = MockMvcRequestBuilders.post("/api/v1/workers/heartbeat");
        if (key != null)
            b.header(header, key);
        return mvc.perform(b).andReturn();
    }

    private java.sql.Timestamp lastSeen(String workerId) {
        return jdbc.queryForObject("SELECT last_seen_at FROM worker_registry WHERE id = ?::uuid",
                java.sql.Timestamp.class, workerId);
    }

    @Test
    void heartbeatUpdatesOnlyTheCallingWorkerAndReturnsActive() throws Exception {
        JsonNode a = enrollOk(adminCreatesToken("hb-a", "site-a"));
        JsonNode b = enrollOk(adminCreatesToken("hb-b", "site-a"));
        String oldTime = "2020-01-01T00:00:00Z";
        jdbc.update("UPDATE worker_registry SET last_seen_at = ?::timestamptz", oldTime);
        java.sql.Timestamp bBefore = lastSeen(b.get("workerId").asText());

        var res = heartbeat(WorkerAuthFilter.HEADER, a.get("apiKey").asText());
        assertEquals(200, res.getResponse().getStatus());
        JsonNode out = body(res);
        assertEquals("ACTIVE", out.get("status").asText());
        assertTrue(out.hasNonNull("lastSeenAt"));
        assertTrue(lastSeen(a.get("workerId").asText()).after(bBefore));
        assertEquals(bBefore, lastSeen(b.get("workerId").asText()));
    }

    @Test
    void heartbeatIgnoresAnyWorkerIdSuppliedByTheCaller() throws Exception {
        JsonNode a = enrollOk(adminCreatesToken("hb-iso-a", "site-a"));
        JsonNode b = enrollOk(adminCreatesToken("hb-iso-b", "site-a"));
        jdbc.update("UPDATE worker_registry SET last_seen_at = '2020-01-01T00:00:00Z'");
        java.sql.Timestamp bBefore = lastSeen(b.get("workerId").asText());

        var res = mvc
                .perform(MockMvcRequestBuilders.post("/api/v1/workers/heartbeat?workerId=" + b.get("workerId").asText())
                        .header(WorkerAuthFilter.HEADER, a.get("apiKey").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"" + b.get("workerId").asText() + "\"}"))
                .andReturn();
        assertEquals(200, res.getResponse().getStatus());
        assertEquals(bBefore, lastSeen(b.get("workerId").asText()));
        assertTrue(lastSeen(a.get("workerId").asText()).after(bBefore));
    }

    @Test
    void heartbeatAcceptsTheApiKeyHeaderAlias() throws Exception {
        String key = enrollOk(adminCreatesToken("hb-alias", "site-a")).get("apiKey").asText();
        assertEquals(200, heartbeat(WorkerAuthFilter.ALT_HEADER, key).getResponse().getStatus());
    }

    @Test
    void heartbeatRejectsMissingInvalidAndRevokedKeys() throws Exception {
        JsonNode w = enrollOk(adminCreatesToken("hb-bad", "site-a"));
        String key = w.get("apiKey").asText();
        int none = heartbeat(WorkerAuthFilter.HEADER, null).getResponse().getStatus();
        assertTrue(none == 401 || none == 403);
        assertEquals(401, heartbeat(WorkerAuthFilter.HEADER, WorkerCredentials.newKey()).getResponse().getStatus());
        assertEquals(401, heartbeat(WorkerAuthFilter.HEADER, "not-a-key").getResponse().getStatus());

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/workers/" + w.get("workerId").asText() + "/revoke")
                .with(SecurityMockMvcRequestPostProcessors.user("root").roles("ADMIN"))).andReturn();
        jdbc.update("UPDATE worker_registry SET last_seen_at = '2020-01-01T00:00:00Z'");
        java.sql.Timestamp before = lastSeen(w.get("workerId").asText());
        assertEquals(401, heartbeat(WorkerAuthFilter.HEADER, key).getResponse().getStatus());
        assertEquals(before, lastSeen(w.get("workerId").asText()));
    }

    @Test
    void adminListDistinguishesActiveStaleAndRevoked() throws Exception {
        JsonNode active = enrollOk(adminCreatesToken("st-active", "site-a"));
        JsonNode stale = enrollOk(adminCreatesToken("st-stale", "site-a"));
        JsonNode revoked = enrollOk(adminCreatesToken("st-revoked", "site-a"));
        jdbc.update("UPDATE worker_registry SET last_seen_at = now() WHERE name = 'st-active'");
        jdbc.update("UPDATE worker_registry SET last_seen_at = now() - interval '1 hour' WHERE name = 'st-stale'");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/workers/" + revoked.get("workerId").asText() + "/revoke")
                .with(SecurityMockMvcRequestPostProcessors.user("root").roles("ADMIN"))).andReturn();

        var res = mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/workers")
                .with(SecurityMockMvcRequestPostProcessors.user("root").roles("ADMIN"))).andReturn();
        java.util.Map<String, String> byName = new java.util.HashMap<>();
        body(res).forEach(n -> byName.put(n.get("name").asText(), n.get("status").asText()));
        assertEquals("ACTIVE", byName.get("st-active"));
        assertEquals("STALE", byName.get("st-stale"));
        assertEquals("REVOKED", byName.get("st-revoked"));
    }
}

