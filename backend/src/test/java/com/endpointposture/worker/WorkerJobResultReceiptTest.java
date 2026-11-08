package com.endpointposture.worker;

import com.endpointposture.EndpointPostureApplication;
import com.endpointposture.job.JobType;
import com.endpointposture.worker.result.WorkerJobResultReceipt;
import com.endpointposture.worker.result.WorkerJobResultReceiptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = EndpointPostureApplication.class)
class WorkerJobResultReceiptTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("endpoint_posture_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "testcontainers-only-secret-at-least-256-bits-long-value");
        registry.add("app.seed-admin.password", () -> "test-admin-password");
        registry.add("app.posture.api-key", () -> "test-agent-key");
        registry.add("app.jobs.workers.enabled", () -> "false");
    }

    @Autowired WorkerJobResultReceiptRepository receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void persistsAndRetrievesReceiptByJobAndAttempt() {
        UUID jobId = createJob();
        UUID workerId = UUID.randomUUID();
        WorkerJobResultReceipt saved = receipts.saveAndFlush(receipt(jobId, workerId, 1));

        WorkerJobResultReceipt loaded = receipts.findByJobIdAndAttempt(jobId, 1).orElseThrow();
        assertEquals(saved.getId(), loaded.getId());
        assertEquals(workerId, loaded.getWorkerId());
        assertEquals(64, loaded.getResultHash().length());
        assertNotNull(loaded.getAcceptedAt());
    }

    @Test
    void databaseRejectsDuplicateJobAndAttempt() {
        UUID jobId = createJob();
        receipts.saveAndFlush(receipt(jobId, UUID.randomUUID(), 1));
        assertThrows(DataIntegrityViolationException.class,
                () -> receipts.saveAndFlush(receipt(jobId, UUID.randomUUID(), 1)));
    }

    @Test
    void databaseRejectsNonPositiveAttemptAndSchemaVersion() {
        UUID jobId = createJob();
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO worker_job_result_receipt
                          (job_id, attempt, worker_id, result_hash, schema_version, collection_outcome)
                        VALUES (?, 0, ?, ?, 1, 'SUCCESS')
                        """, jobId, UUID.randomUUID(), "a".repeat(64)));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO worker_job_result_receipt
                          (job_id, attempt, worker_id, result_hash, schema_version, collection_outcome)
                        VALUES (?, 2, ?, ?, 0, 'SUCCESS')
                        """, jobId, UUID.randomUUID(), "a".repeat(64)));
    }

    @Test
    void databaseRestrictsOutcomeToSupportedValues() {
        UUID jobId = createJob();
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO worker_job_result_receipt
                          (job_id, attempt, worker_id, result_hash, schema_version, collection_outcome)
                        VALUES (?, 1, ?, ?, 1, 'UNKNOWN')
                        """, jobId, UUID.randomUUID(), "a".repeat(64)));
        receipts.saveAndFlush(receipt(jobId, UUID.randomUUID(), 1));
        UUID secondJob = createJob();
        WorkerJobResultReceipt partial = receipt(secondJob, UUID.randomUUID(), 1);
        partial.setCollectionOutcome(WorkerResultRequest.CollectionOutcome.PARTIAL);
        receipts.saveAndFlush(partial);
        UUID thirdJob = createJob();
        WorkerJobResultReceipt failed = receipt(thirdJob, UUID.randomUUID(), 1);
        failed.setCollectionOutcome(WorkerResultRequest.CollectionOutcome.FAILED);
        receipts.saveAndFlush(failed);
    }


    @Test
    void validatesProcessExitCodeAndCollectionOutcomeConsistency() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        JsonNode payload = mapper.createObjectNode();

        assertValid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of(), 0));
        assertValid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(),
                List.of("Informational warning"), 0));
        assertValid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.PARTIAL, payload, List.of(),
                List.of("Missing optional evidence"), 0));
        assertValid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.PARTIAL, payload,
                List.of("One source failed"), List.of(), 0));
        assertValid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.PARTIAL, payload, List.of(),
                List.of("PowerShell reported partial collection"), 7));
        assertValid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.PARTIAL, payload,
                List.of("One source failed"), List.of(), 7));
        assertValid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.FAILED, mapper.nullNode(),
                List.of("PowerShell exited with code 1"), List.of(), 1));
        assertValid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.FAILED, mapper.nullNode(),
                List.of("PowerShell exited with code 1"), List.of(), 1));

        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of(), 2));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.FAILED, payload, List.of("failed"), List.of(), 0));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.FAILED, payload, List.of(), List.of(), 1));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.FAILED, payload, List.of("  "), List.of(), 1));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of("unexpected"), List.of(), 0));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.PARTIAL, payload, List.of(), List.of(), 0));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of(), -1));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of(), null));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                null, payload, List.of(), List.of(), 0));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, null, List.of(), 0));
        assertInvalid(validator, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), null, 0));
    }

    private void assertValid(Validator validator, WorkerResultRequest request) {
        assertTrue(validator.validate(request).isEmpty(), request.toString());
    }

    private void assertInvalid(Validator validator, WorkerResultRequest request) {
        assertFalse(validator.validate(request).isEmpty(), request.toString());
    }

    @Test
    void hashIgnoresObjectKeyOrderRecursively() throws Exception {
        WorkerResultRequest first = request(mapper.readTree("""
                {"z":1,"nested":{"b":2,"a":3},"a":[{"y":true,"x":false}]}
                """));
        WorkerResultRequest reordered = request(mapper.readTree("""
                {"a":[{"x":false,"y":true}],"nested":{"a":3,"b":2},"z":1}
                """));
        assertEquals(first.sha256Hex(mapper), reordered.sha256Hex(mapper));
    }

    @Test
    void hashChangesWhenMeaningfulEnvelopeContentChanges() throws Exception {
        JsonNode payload = mapper.readTree("{\"firewall\":true}");
        WorkerResultRequest baseline = request(payload);
        assertHashChanges(baseline, new WorkerResultRequest(2, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of(), 0));
        assertHashChanges(baseline, new WorkerResultRequest(1, 2, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of(), 0));
        assertHashChanges(baseline, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.PARTIAL, payload, List.of(), List.of(), 0));
        assertHashChanges(baseline, new WorkerResultRequest(1, 1, JobType.HARDWARE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of(), 0));
        assertHashChanges(baseline, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, mapper.readTree("{\"firewall\":false}"),
                List.of(), List.of(), 0));
        assertHashChanges(baseline, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of("error"), List.of(), 0));
        assertHashChanges(baseline, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of("warning"), 0));
        assertHashChanges(baseline, new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of(), 1));
    }

    @Test
    void validatesRequiredFieldsPositiveNumbersAndSizeLimits() throws Exception {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        WorkerResultRequest valid = request(mapper.readTree("{}"));
        assertTrue(validator.validate(valid).isEmpty());

        WorkerResultRequest badNumbers = new WorkerResultRequest(0, 0, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, mapper.readTree("{}"), List.of(), List.of(), 0);
        assertFalse(validator.validate(badNumbers).isEmpty());

        WorkerResultRequest nullRequiredFields = new WorkerResultRequest(1, 1, null,
                null, null, null, null, null);
        assertFalse(validator.validate(nullRequiredFields).isEmpty());

        WorkerResultRequest jsonNullPayload = new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, mapper.nullNode(), List.of(), List.of(), 0);
        assertFalse(validator.validate(jsonNullPayload).isEmpty());
        WorkerResultRequest partialNullPayload = new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.PARTIAL, mapper.nullNode(),
                List.of(), List.of("partial evidence"), 0);
        assertFalse(validator.validate(partialNullPayload).isEmpty());
        WorkerResultRequest missingFailedPayload = new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.FAILED, null,
                List.of("PowerShell exited with code 1"), List.of(), 1);
        assertFalse(validator.validate(missingFailedPayload).isEmpty());

        WorkerResultRequest oversizedPayload = request(mapper.readTree(
                "{\"data\":\"" + "x".repeat(WorkerResultRequest.MAX_REPORT_PAYLOAD_BYTES) + "\"}"));
        assertFalse(validator.validate(oversizedPayload).isEmpty());

        WorkerResultRequest tooManyErrors = new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.FAILED, mapper.readTree("{}"),
                java.util.Collections.nCopies(101, "error"), List.of(), 1);
        assertFalse(validator.validate(tooManyErrors).isEmpty());

        WorkerResultRequest tooMuchDiagnosticText = new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.FAILED, mapper.readTree("{}"),
                List.of("x".repeat(WorkerResultRequest.MAX_DIAGNOSTIC_TEXT_BYTES + 1)), List.of(), 1);
        assertFalse(validator.validate(tooMuchDiagnosticText).isEmpty());
    }

    private void assertHashChanges(WorkerResultRequest baseline, WorkerResultRequest changed) {
        assertNotEquals(baseline.sha256Hex(mapper), changed.sha256Hex(mapper));
    }

    private UUID createJob() {
        UUID endpointId = jdbc.queryForObject(
                "INSERT INTO endpoint(mac_address) VALUES (?) RETURNING id",
                UUID.class, randomMac());
        return jdbc.queryForObject("""
                INSERT INTO posture_job(endpoint_id, job_type, status, priority, attempt_count, max_attempts)
                VALUES (?, 'POSTURE_CHECK', 'QUEUED', 0, 0, 3) RETURNING id
                """, UUID.class, endpointId);
    }

    private WorkerJobResultReceipt receipt(UUID jobId, UUID workerId, int attempt) {
        return WorkerJobResultReceipt.builder()
                .jobId(jobId).attempt(attempt).workerId(workerId)
                .resultHash("a".repeat(64)).schemaVersion(1)
                .collectionOutcome(WorkerResultRequest.CollectionOutcome.SUCCESS).build();
    }

    private WorkerResultRequest request(JsonNode payload) {
        return new WorkerResultRequest(1, 1, JobType.POSTURE_CHECK,
                WorkerResultRequest.CollectionOutcome.SUCCESS, payload, List.of(), List.of(), 0);
    }

    private String randomMac() {
        return "02:00:" + UUID.randomUUID().toString().substring(0, 2) + ":"
                + UUID.randomUUID().toString().substring(0, 2) + ":"
                + UUID.randomUUID().toString().substring(0, 2) + ":"
                + UUID.randomUUID().toString().substring(0, 2);
    }
}
