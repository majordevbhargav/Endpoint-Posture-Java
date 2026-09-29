package com.endpointposture.job;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises PostureJobRepository.findNextClaimable()'s FOR UPDATE SKIP
 * LOCKED clause against a real Postgres instance. This is Postgres-specific
 * SQL (marked nativeQuery = true precisely because JPQL can't express it),
 * so an in-memory/H2 test would not actually prove the concurrency
 * guarantee this query exists for. Runs the real Flyway migrations, the
 * same schema production uses, rather than a hand-rolled test schema.
 */
@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class PostureJobRepositoryConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("endpoint_posture_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The app fails fast without these three - harmless test-only values.
        registry.add("app.jwt.secret", () -> "testcontainers-only-secret-at-least-256-bits-long-value");
        registry.add("app.seed-admin.password", () -> "test-admin-password");
        registry.add("app.posture.api-key", () -> "test-agent-key");
        registry.add("app.jobs.workers.enabled", () -> "false");
    }

    @Autowired
    JobService jobService;
    @Autowired
    PostureJobRepository jobRepository;
    @Autowired
    EndpointRepository endpointRepository;

    private UUID endpointAId;
    private UUID endpointBId;

    @BeforeEach
    void setUp() {
        endpointAId = endpointRepository.save(Endpoint.builder().macAddress(randomMac()).build()).getId();
        endpointBId = endpointRepository.save(Endpoint.builder().macAddress(randomMac()).build()).getId();
    }

    @AfterEach
    void tearDown() {
        jobRepository.deleteAll();
        endpointRepository.deleteAll();
    }

    @Test
    void concurrentClaimsNeverReturnTheSameJobTwice() throws Exception {
        PostureJob jobA = jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob jobB = jobService.enqueue(endpointBId, JobType.POSTURE_CHECK, 0);

        int workerCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            List<Future<Optional<PostureJob>>> futures = IntStream.range(0, workerCount)
                    .mapToObj(i -> pool.submit(() -> {
                        startGate.await();
                        return jobService.claimNextJob();
                    }))
                    .collect(Collectors.toList());

            startGate.countDown();

            List<UUID> claimedIds = new ArrayList<>();
            for (Future<Optional<PostureJob>> f : futures) {
                f.get(10, TimeUnit.SECONDS).ifPresent(j -> claimedIds.add(j.getId()));
            }

            // Exactly the two queued jobs were claimed across all workers -
            // never zero, never duplicated - and every other concurrent
            // attempt got nothing (SKIP LOCKED, not "wait then claim again").
            assertEquals(2, claimedIds.size(), "expected exactly 2 jobs claimed across all workers");
            Set<UUID> distinct = Set.copyOf(claimedIds);
            assertEquals(2, distinct.size(), "the same job was claimed by more than one worker");
            assertTrue(distinct.containsAll(Set.of(jobA.getId(), jobB.getId())));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void claimedJobIsMarkedRunningWithIncrementedAttemptCount() {
        PostureJob job = jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);

        Optional<PostureJob> claimed = jobService.claimNextJob();

        assertTrue(claimed.isPresent());
        assertEquals(job.getId(), claimed.get().getId());
        assertEquals(JobStatus.RUNNING, claimed.get().getStatus());
        assertEquals(1, claimed.get().getAttemptCount());
        assertNotNull(claimed.get().getStartedAt());
    }

    @Test
    void higherPriorityJobIsClaimedFirst() {
        jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob high = jobService.enqueue(endpointBId, JobType.POSTURE_CHECK, 10);

        Optional<PostureJob> first = jobService.claimNextJob();

        assertTrue(first.isPresent());
        assertEquals(high.getId(), first.get().getId());
    }

    @Test
    void noEligibleJobsReturnsEmpty() {
        assertTrue(jobService.claimNextJob().isEmpty());
    }

    private String randomMac() {
        byte[] bytes = new byte[6];
        new Random().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }
}