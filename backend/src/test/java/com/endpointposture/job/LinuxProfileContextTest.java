package com.endpointposture.job;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.system.SystemHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real application with the "linux" profile and proves that the
 * Windows-only machinery is not wired: no worker pool (so no powershell.exe),
 * no recheck scheduler (so no jobs nobody would claim).
 *
 * Deliberately does NOT set app.jobs.workers.enabled / app.jobs.recheck.enabled:
 * those must come from application-linux.yml, otherwise this test proves nothing.
 */
@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
@ActiveProfiles("linux")
class LinuxProfileContextTest {

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
        r.add("app.retention.enabled", () -> "false");
    }

    @Autowired ApplicationContext ctx;
    @Autowired SystemHealthService health;

    @Test
    void noWorkerPoolBeanUnderTheLinuxProfile() {
        assertEquals(0, ctx.getBeanNamesForType(JobWorkerPool.class).length);
    }

    @Test
    void noRecheckSchedulerBeanUnderTheLinuxProfile() {
        assertEquals(0, ctx.getBeanNamesForType(RecheckScheduler.class).length);
    }

    @Test
    void jobWorkerItselfStillExistsSoRemoteWorkersCanReuseIt() {
        assertEquals(1, ctx.getBeanNamesForType(JobWorker.class).length);
    }

    @Test
    void healthReportsRemotePendingWithNoQueuedJobs() {
        var snapshot = health.snapshot();

        assertNotNull(snapshot);
        assertEquals(false, snapshot.workers().enabled());
        assertEquals("REMOTE_PENDING", snapshot.workers().mode());
        assertEquals(0, snapshot.queue().queued());
        assertTrue(snapshot.warnings().stream()
                .noneMatch(w -> w.contains("No job worker is available")));
    }
}
