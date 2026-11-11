package com.endpointposture.worker;

import com.endpointposture.diagnostic.DiagnosticService;
import com.endpointposture.diagnostic.dto.DiagnosticReportRequest;
import com.endpointposture.hardware.HardwareIngestService;
import com.endpointposture.hardware.dto.HardwareReportRequest;
import com.endpointposture.indicator.SecurityIndicatorService;
import com.endpointposture.indicator.dto.SecurityReportRequest;
import com.endpointposture.job.JobService;
import com.endpointposture.job.JobStatus;
import com.endpointposture.job.JobType;
import com.endpointposture.job.PostureJob;
import com.endpointposture.job.PostureJobRepository;
import com.endpointposture.posture.PostureIngestService;
import com.endpointposture.posture.dto.PostureReportRequest;
import com.endpointposture.worker.result.WorkerJobResultReceipt;
import com.endpointposture.worker.result.WorkerJobResultReceiptRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Atomically accepts one result for the current worker lease. Evidence,
 * lifecycle transition and durable receipt share one database transaction.
 */
@Service
public class WorkerResultService {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final PostureJobRepository jobs;
    private final WorkerJobResultReceiptRepository receipts;
    private final JobService jobService;
    private final PostureIngestService posture;
    private final HardwareIngestService hardware;
    private final DiagnosticService diagnostics;
    private final SecurityIndicatorService securityIndicators;
    private final ObjectMapper mapper;

    public WorkerResultService(PostureJobRepository jobs, WorkerJobResultReceiptRepository receipts,
            JobService jobService, PostureIngestService posture, HardwareIngestService hardware,
            DiagnosticService diagnostics, SecurityIndicatorService securityIndicators, ObjectMapper mapper) {
        this.jobs = jobs;
        this.receipts = receipts;
        this.jobService = jobService;
        this.posture = posture;
        this.hardware = hardware;
        this.diagnostics = diagnostics;
        this.securityIndicators = securityIndicators;
        this.mapper = mapper;
    }

    @Transactional
    public WorkerResultResponse submit(UUID jobId, WorkerPrincipal worker, WorkerResultRequest request) {
        if (jobId == null || worker == null || request == null) {
            throw new IllegalArgumentException("A job, authenticated worker and result envelope are required");
        }
        if (request.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported result schemaVersion");
        }
        if (request.jobType() == null || request.collectionOutcome() == null
                || request.errors() == null || request.warnings() == null
                || !request.isReportPayloadValid()) {
            throw new IllegalArgumentException("The result envelope is incomplete or reportPayload is invalid for its outcome");
        }
        if (!request.isProcessOutcomeConsistent()) {
            throw new IllegalArgumentException("processExitCode, collectionOutcome, errors and warnings are inconsistent");
        }

        String resultHash = request.sha256Hex(mapper);

        // Check historical receipts before touching the job row. Receipts intentionally
        // outlive jobs, so a retry must remain acknowledgeable after job retention deletes
        // the lifecycle row.
        WorkerResultResponse priorReceipt = existingReceiptResponse(
                jobId, request.attempt(), worker, resultHash);
        if (priorReceipt != null) return priorReceipt;

        PostureJob job = jobs.findByIdForResultUpdate(jobId)
                .orElseThrow(() -> new WorkerResultConflictException("Job is not available for this result"));

        // A concurrent first submission may have committed while this request waited
        // for the job lock. Recheck under the lock before validating current ownership.
        priorReceipt = existingReceiptResponse(jobId, request.attempt(), worker, resultHash);
        if (priorReceipt != null) return priorReceipt;

        if (job.getStatus() != JobStatus.RUNNING
                || job.getAttemptCount() != request.attempt()
                || !worker.id().toString().equals(job.getClaimedBy())
                || !worker.shard().equals(job.getShard())) {
            throw new WorkerResultConflictException("Attempt is stale, expired or not owned by this worker");
        }
        if (request.jobType() != job.getJobType()) {
            throw new IllegalArgumentException("jobType does not match the claimed job");
        }

        UUID endpointId = job.getEndpoint().getId();
        WorkerResultRequest.CollectionOutcome outcome = request.collectionOutcome();
        if (outcome == WorkerResultRequest.CollectionOutcome.FAILED) {
            String reason = request.errors().stream().filter(s -> s != null && !s.isBlank())
                    .findFirst().orElse("Worker reported collection failure");
            if (!jobService.failFenced(jobId, worker.claimId(), request.attempt(), reason)) {
                throw new WorkerResultConflictException("Attempt is no longer current");
            }
            recordFailure(job.getJobType(), endpointId, jobId, reason);
        } else {
            ingest(job.getJobType(), endpointId, jobId, request.reportPayload(), request.errors(), request.warnings());
            if (!jobService.completeFenced(jobId, worker.claimId(), request.attempt())) {
                throw new WorkerResultConflictException("Attempt is no longer current");
            }
        }

        WorkerJobResultReceipt receipt = WorkerJobResultReceipt.builder()
                .jobId(jobId).attempt(request.attempt()).workerId(worker.id())
                .resultHash(resultHash).schemaVersion(request.schemaVersion())
                .collectionOutcome(outcome).acceptedAt(Instant.now().truncatedTo(ChronoUnit.MICROS)).build();
        try {
            return response(receipts.saveAndFlush(receipt));
        } catch (DataIntegrityViolationException e) {
            // The database uniqueness constraint is the final guard; never claim a racing insert succeeded.
            throw new WorkerResultConflictException("A result receipt already exists for this attempt");
        }
    }

    private void ingest(JobType type, UUID endpointId, UUID jobId, JsonNode payload, java.util.List<String> errors, java.util.List<String> warnings) {
        try {
            switch (type) {
                case POSTURE_CHECK -> {
                    PostureReportRequest req = mapper.treeToValue(payload, PostureReportRequest.class);
                    if (req.status() == null || req.checks() == null || req.checks().isEmpty())
                        throw new IllegalArgumentException("Posture payload requires status and checks");
                    posture.ingestForWorker(endpointId, jobId, req, errors, warnings);
                }
                case HARDWARE_CHECK -> {
                    HardwareReportRequest req = mapper.treeToValue(payload, HardwareReportRequest.class);
                    if (req.cpuMemory() == null || req.storage() == null)
                        throw new IllegalArgumentException("Hardware payload requires cpuMemory and storage");
                    hardware.ingestForWorker(endpointId, jobId, req, errors, warnings);
                }
                case DIAGNOSTIC_CHECK -> {
                    DiagnosticReportRequest req = mapper.treeToValue(payload, DiagnosticReportRequest.class);
                    if (req.status() == null)
                        throw new IllegalArgumentException("Diagnostic payload requires status");
                    diagnostics.ingestForWorker(endpointId, jobId, req, errors, warnings);
                }
                case SECURITY_CHECK -> {
                    SecurityReportRequest req = mapper.treeToValue(payload, SecurityReportRequest.class);
                    if (req.status() == null)
                        throw new IllegalArgumentException("Security-indicator payload requires status");
                    securityIndicators.ingestForWorker(endpointId, jobId, req, errors, warnings);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid reportPayload for " + type, e);
        }
    }

    private void recordFailure(JobType type, UUID endpointId, UUID jobId, String reason) {
        switch (type) {
            case POSTURE_CHECK -> posture.recordFailureForWorker(endpointId, jobId, reason);
            case HARDWARE_CHECK -> hardware.recordFailureForWorker(endpointId, jobId, reason);
            case DIAGNOSTIC_CHECK -> diagnostics.recordFailure(endpointId, jobId, reason);
            case SECURITY_CHECK -> securityIndicators.recordFailure(endpointId, jobId, reason);
        }
    }

    /**
     * Returns the durable acknowledgement when this exact worker already submitted
     * this attempt, or rejects a conflicting historical receipt. A null return means
     * no receipt exists yet.
     */
    private WorkerResultResponse existingReceiptResponse(
            UUID jobId, int attempt, WorkerPrincipal worker, String resultHash) {
        return receipts.findByJobIdAndAttempt(jobId, attempt)
                .map(receipt -> {
                    if (receipt.getWorkerId().equals(worker.id())
                            && receipt.getResultHash().equals(resultHash)) {
                        return response(receipt);
                    }
                    throw new WorkerResultConflictException(
                            "A different result is already recorded for this attempt");
                })
                .orElse(null);
    }

    private WorkerResultResponse response(WorkerJobResultReceipt receipt) {
        return new WorkerResultResponse(true, receipt.getId(), receipt.getJobId(), receipt.getAttempt(),
                receipt.getCollectionOutcome(), receipt.getAcceptedAt());
    }

    public static class WorkerResultConflictException extends RuntimeException {
        public WorkerResultConflictException(String message) { super(message); }
    }
}
