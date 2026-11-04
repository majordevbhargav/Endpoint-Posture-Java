package com.endpointposture.job;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PostureJob entity maps claimed_by / lease_expires_at to the real V25
 * columns (Hibernate runs against the Flyway-built schema), and jobs without a
 * lease still persist.
 */
@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class PostureJobLeaseMappingTest {

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
    }

    @Autowired PostureJobRepository jobs;
    @Autowired EndpointRepository endpoints;

    private Endpoint endpoint() {
        return endpoints.save(Endpoint.builder()
                .macAddress("AA:BB:" + UUID.randomUUID().toString().substring(0, 8)).build());
    }

    @Test
    void leaseFieldsRoundTrip() {
        Instant expiry = Instant.now().plus(60, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.MILLIS);
        PostureJob saved = jobs.saveAndFlush(PostureJob.builder()
                .endpoint(endpoint()).claimedBy("worker-01").leaseExpiresAt(expiry).build());

        PostureJob loaded = jobs.findById(saved.getId()).orElseThrow();
        assertEquals("worker-01", loaded.getClaimedBy());
        assertEquals(expiry, loaded.getLeaseExpiresAt());
    }

    @Test
    void jobWithoutLeaseStillPersistsWithNullLeaseFields() {
        PostureJob saved = jobs.saveAndFlush(PostureJob.builder().endpoint(endpoint()).build());

        PostureJob loaded = jobs.findById(saved.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, loaded.getStatus());
        assertNull(loaded.getClaimedBy());
        assertNull(loaded.getLeaseExpiresAt());
    }
}
