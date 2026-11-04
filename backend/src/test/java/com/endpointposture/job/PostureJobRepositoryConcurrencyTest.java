package com.endpointposture.job;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
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
    @Autowired
    JdbcTemplate jdbcTemplate;

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

    @Test
    void concurrentLeasedClaimsGiveEachJobExactlyOneOwnerAndPersistTheLease() throws Exception {
        PostureJob jobA = jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob jobB = jobService.enqueue(endpointBId, JobType.POSTURE_CHECK, 0);

        int workerCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            List<Future<Optional<PostureJob>>> futures = IntStream.range(0, workerCount)
                    .mapToObj(i -> pool.submit(() -> {
                        startGate.await();
                        return jobService.claimNextJob("worker-" + i, Duration.ofMinutes(2));
                    }))
                    .collect(Collectors.toList());
            startGate.countDown();

            List<PostureJob> claimed = new ArrayList<>();
            for (Future<Optional<PostureJob>> f : futures) {
                f.get(10, TimeUnit.SECONDS).ifPresent(claimed::add);
            }

            assertEquals(2, claimed.size());
            assertEquals(2, claimed.stream().map(PostureJob::getId).distinct().count());
            assertEquals(2, claimed.stream().map(PostureJob::getClaimedBy).distinct().count(),
                    "each job must have a different owner");

            for (UUID id : List.of(jobA.getId(), jobB.getId())) {
                PostureJob stored = jobRepository.findById(id).orElseThrow();
                assertEquals(JobStatus.RUNNING, stored.getStatus());
                assertNotNull(stored.getClaimedBy());
                assertNotNull(stored.getLeaseExpiresAt());
                assertEquals(1, stored.getAttemptCount());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void currentLeasedWorkerCanCompleteAndTheLeaseIsReleased() {
        PostureJob queued = jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob claimed = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();

        assertEquals(queued.getId(), claimed.getId());
        assertTrue(jobService.completeFenced(claimed.getId(), "worker-A", 1));

        PostureJob stored = jobRepository.findById(claimed.getId()).orElseThrow();
        assertEquals(JobStatus.COMPLETE, stored.getStatus());
        assertNotNull(stored.getCompletedAt());
        assertNull(stored.getErrorMessage());
        assertNull(stored.getClaimedBy());
        assertNull(stored.getLeaseExpiresAt());
    }

    @Test
    void fencedCompletionRejectsWrongWorkerAndWrongAttempt() {
        jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob claimed = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();

        assertFalse(jobService.completeFenced(claimed.getId(), "worker-B", 1));
        assertFalse(jobService.completeFenced(claimed.getId(), "worker-A", 2));
        assertEquals(JobStatus.RUNNING, jobRepository.findById(claimed.getId()).orElseThrow().getStatus());
    }

    @Test
    void fencedCompletionRejectsAnExpiredLease() {
        PostureJob claimed = jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob running = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();
        jdbcTemplate.update(
                "UPDATE posture_job SET lease_expires_at = now() - INTERVAL '1 second' WHERE id = ?",
                running.getId());

        assertFalse(jobService.completeFenced(running.getId(), "worker-A", 1));
        assertEquals(JobStatus.RUNNING, jobRepository.findById(claimed.getId()).orElseThrow().getStatus());
    }

    @Test
    void completedJobRejectsASecondSubmission() {
        PostureJob claimed = jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob running = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();

        assertTrue(jobService.completeFenced(running.getId(), "worker-A", 1));
        assertFalse(jobService.completeFenced(running.getId(), "worker-A", 1));
    }

    @Test
    void acceptedFailureRequeuesAndClearsWorkerLease() {
        jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob running = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();

        assertTrue(jobService.failFenced(running.getId(), "worker-A", 1, "agent execution failed"));

        PostureJob stored = jobRepository.findById(running.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, stored.getStatus());
        assertEquals("agent execution failed", stored.getErrorMessage());
        assertNotNull(stored.getNextAttemptAt());
        assertNull(stored.getCompletedAt());
        assertNull(stored.getClaimedBy());
        assertNull(stored.getLeaseExpiresAt());
    }

    @Test
    void acceptedFailureOnLastAttemptMarksFailedAndClearsWorkerLease() {
        PostureJob queued = jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        jdbcTemplate.update("UPDATE posture_job SET attempt_count = 2 WHERE id = ?", queued.getId());
        PostureJob running = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();

        assertEquals(3, running.getAttemptCount());
        assertTrue(jobService.failFenced(running.getId(), "worker-A", 3, "final attempt failed"));

        PostureJob stored = jobRepository.findById(running.getId()).orElseThrow();
        assertEquals(JobStatus.FAILED, stored.getStatus());
        assertNotNull(stored.getCompletedAt());
        assertNull(stored.getClaimedBy());
        assertNull(stored.getLeaseExpiresAt());
    }

    @Test
    void staleAttemptAndCurrentAttemptRaceAllowsOnlyTheCurrentWorkerToComplete() throws Exception {
        jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob firstAttempt = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();

        // Simulate lease recovery/requeue between attempts. Recovery itself is
        // outside this stage; the test establishes the state it must leave behind.
        jdbcTemplate.update("""
                UPDATE posture_job
                   SET status = 'QUEUED', claimed_by = NULL, lease_expires_at = NULL,
                       next_attempt_at = NULL
                 WHERE id = ?
                """, firstAttempt.getId());

        PostureJob secondAttempt = jobService.claimNextJob("worker-B", Duration.ofMinutes(5)).orElseThrow();
        assertEquals(2, secondAttempt.getAttemptCount());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Future<Boolean> stale = pool.submit(() -> {
                startGate.await();
                return jobService.completeFenced(firstAttempt.getId(), "worker-A", 1);
            });
            Future<Boolean> current = pool.submit(() -> {
                startGate.await();
                return jobService.completeFenced(secondAttempt.getId(), "worker-B", 2);
            });
            startGate.countDown();

            boolean staleAccepted = stale.get(10, TimeUnit.SECONDS);
            boolean currentAccepted = current.get(10, TimeUnit.SECONDS);
            assertFalse(staleAccepted, "superseded attempt must be fenced out");
            assertTrue(currentAccepted, "current owner and fencing token must be accepted");
            assertEquals(1, List.of(staleAccepted, currentAccepted).stream().filter(Boolean::booleanValue).count());
            assertEquals(JobStatus.COMPLETE,
                    jobRepository.findById(secondAttempt.getId()).orElseThrow().getStatus());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void attemptOnlyRecoveryRequeuesWithBackoffAndClearsLease() {
        jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob running = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();

        assertTrue(jobService.failAttempt(running.getId(), 1, "stale worker recovered"));

        PostureJob stored = jobRepository.findById(running.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, stored.getStatus());
        assertEquals("stale worker recovered", stored.getErrorMessage());
        assertTrue(stored.getNextAttemptAt().isAfter(java.time.Instant.now().plusSeconds(50)));
        assertTrue(stored.getNextAttemptAt().isBefore(java.time.Instant.now().plusSeconds(70)));
        assertNull(stored.getCompletedAt());
        assertNull(stored.getClaimedBy());
        assertNull(stored.getLeaseExpiresAt());
    }

    @Test
    void attemptOnlyRecoveryCapsBackoffAtFifteenMinutes() {
        PostureJob queued = jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        jdbcTemplate.update("UPDATE posture_job SET max_attempts = 30, attempt_count = 19 WHERE id = ?", queued.getId());
        PostureJob running = jobService.claimNextJob().orElseThrow();

        assertEquals(20, running.getAttemptCount());
        assertTrue(jobService.failAttempt(running.getId(), 20, "retry"));

        PostureJob stored = jobRepository.findById(running.getId()).orElseThrow();
        assertTrue(stored.getNextAttemptAt().isAfter(java.time.Instant.now().plus(Duration.ofMinutes(14))));
        assertTrue(stored.getNextAttemptAt().isBefore(java.time.Instant.now().plus(Duration.ofMinutes(16))));
        assertNull(stored.getCompletedAt());
    }

    @Test
    void attemptOnlyRecoveryMarksLastAttemptFailedAndClearsLease() {
        PostureJob queued = jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        jdbcTemplate.update("UPDATE posture_job SET attempt_count = 2 WHERE id = ?", queued.getId());
        PostureJob running = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();

        assertTrue(jobService.failAttempt(running.getId(), 3, "final stale attempt"));

        PostureJob stored = jobRepository.findById(running.getId()).orElseThrow();
        assertEquals(JobStatus.FAILED, stored.getStatus());
        assertNotNull(stored.getCompletedAt());
        assertNull(stored.getNextAttemptAt());
        assertNull(stored.getClaimedBy());
        assertNull(stored.getLeaseExpiresAt());
    }

    @Test
    void attemptOnlyCompletionClearsLeaseAndRejectsSubsequentRecovery() {
        jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob running = jobService.claimNextJob("worker-A", Duration.ofMinutes(5)).orElseThrow();

        assertTrue(jobService.completeAttempt(running.getId(), 1));
        assertFalse(jobService.failAttempt(running.getId(), 1, "late recovery"));

        PostureJob stored = jobRepository.findById(running.getId()).orElseThrow();
        assertEquals(JobStatus.COMPLETE, stored.getStatus());
        assertNotNull(stored.getCompletedAt());
        assertNull(stored.getClaimedBy());
        assertNull(stored.getLeaseExpiresAt());
    }

    @Test
    void oldAttemptCannotCompleteOrFailANewerRunningAttempt() {
        jobService.enqueue(endpointAId, JobType.POSTURE_CHECK, 0);
        PostureJob first = jobService.claimNextJob().orElseThrow();
        assertTrue(jobService.failAttempt(first.getId(), 1, "requeue first attempt"));

        PostureJob second = jobService.claimNextJob().orElseThrow();
        assertEquals(2, second.getAttemptCount());

        assertFalse(jobService.completeAttempt(second.getId(), 1));
        assertFalse(jobService.failAttempt(second.getId(), 1, "late first attempt"));

        PostureJob stored = jobRepository.findById(second.getId()).orElseThrow();
        assertEquals(JobStatus.RUNNING, stored.getStatus());
        assertEquals(2, stored.getAttemptCount());
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