package com.endpointposture.worker;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.job.JobService;
import com.endpointposture.job.JobType;
import com.endpointposture.job.PostureJob;
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
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes=EndpointPostureApplication.class)
class WorkerRegistryServiceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("endpoint_posture_test").withUsername("test").withPassword("test");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("app.jwt.secret",()->"testcontainers-only-secret-at-least-256-bits-long-value");
        r.add("app.seed-admin.password",()->"test-admin-password");
        r.add("app.posture.api-key",()->"test-agent-key");
        r.add("app.jobs.workers.enabled",()->"false");
        r.add("app.jobs.recheck.enabled",()->"false");
        r.add("app.retention.enabled",()->"false");
    }
    @Autowired WorkerRegistryService registry;
    @Autowired JobService jobService;
    @Autowired JdbcTemplate jdbc;
    @AfterEach void cleanUp() {
        jdbc.update("DELETE FROM posture_job"); jdbc.update("DELETE FROM endpoint");
        jdbc.update("DELETE FROM worker_enrollment_token"); jdbc.update("DELETE FROM worker_registry");
        jdbc.update("DELETE FROM worker_auth_event");
    }
    private WorkerRegistryService.Enrolled enrolled(String name,String shard) {
        return registry.enroll(registry.createEnrollmentToken(name,shard,10,"admin").token());
    }
    @Test void tokenWorksOnceThenIsRejected() {
        var t=registry.createEnrollmentToken("w-one","default",10,"admin");
        var e=registry.enroll(t.token());
        assertTrue(WorkerCredentials.looksLikeKey(e.apiKey()));
        assertThrows(WorkerRegistryService.EnrollmentRejectedException.class,()->registry.enroll(t.token()));
    }
    @Test void expiredOrUnknownTokenIsRejected() {
        var t=registry.createEnrollmentToken("w-exp","default",10,"admin");
        jdbc.update("UPDATE worker_enrollment_token SET expires_at=now()-interval '1 minute'");
        assertThrows(WorkerRegistryService.EnrollmentRejectedException.class,()->registry.enroll(t.token()));
        assertThrows(WorkerRegistryService.EnrollmentRejectedException.class,
                ()->registry.enroll(WorkerCredentials.newEnrollmentToken()));
    }
    @Test void onlyHashOfKeyIsStored() {
        var e=enrolled("w-hash","default");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM worker_registry WHERE key_hash=?",Integer.class,e.apiKey()));
        assertEquals(WorkerRegistryService.Status.OK,registry.authenticate(e.apiKey()).status());
    }
    @Test void revokedKeyStopsWorkingAndUnknownKeyIsUnknown() {
        var e=enrolled("w-rev","default");
        registry.revoke(e.workerId(),"admin"); registry.revoke(e.workerId(),"admin");
        assertEquals(WorkerRegistryService.Status.REVOKED,registry.authenticate(e.apiKey()).status());
        assertEquals(WorkerRegistryService.Status.UNKNOWN_KEY,registry.authenticate(WorkerCredentials.newKey()).status());
        assertThrows(WorkerRegistryService.WorkerNotFoundException.class,()->registry.revoke(UUID.randomUUID(),"admin"));
    }
    @Test void duplicateNamesAndBadNamesAreRefused() {
        enrolled("w-dup","default");
        assertThrows(IllegalStateException.class,()->registry.createEnrollmentToken("W-DUP","default",10,"admin"));
        assertThrows(IllegalArgumentException.class,()->registry.createEnrollmentToken("bad name!","default",10,"admin"));
    }
    @Test void workerClaimsOnlyItsShardAndOnlyOwnerCanComplete() {
        UUID ep=jdbc.queryForObject("INSERT INTO endpoint(mac_address,ip_address,connected) VALUES ('CC:00:00:00:00:01','10.0.0.1',true) RETURNING id",UUID.class);
        PostureJob job=jobService.enqueue(ep,JobType.POSTURE_CHECK,0);
        jdbc.update("UPDATE posture_job SET shard='site-a' WHERE id=?",job.getId());
        var wrong=enrolled("w-wrong","site-b"); var right=enrolled("w-right","site-a");
        assertTrue(jobService.claimNextJobInShard(wrong.workerId().toString(),"site-b",Duration.ofMinutes(5)).isEmpty());
        Optional<PostureJob> claimed=jobService.claimNextJobInShard(right.workerId().toString(),"site-a",Duration.ofMinutes(5));
        assertTrue(claimed.isPresent());
        int attempt=claimed.get().getAttemptCount();
        assertFalse(jobService.completeFenced(job.getId(),wrong.workerId().toString(),attempt));
        assertTrue(jobService.completeFenced(job.getId(),right.workerId().toString(),attempt));
    }
}
