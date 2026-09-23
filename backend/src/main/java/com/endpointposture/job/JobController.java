package com.endpointposture.job;

import com.endpointposture.job.dto.JobResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API for the job queue: {@code /api/v1/jobs}.
 *
 * <p>Manual enqueueing exists for testing the dispatch pipeline; later,
 * the ISE session watcher enqueues jobs automatically.</p>
 */
@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "The posture-check job queue. Manual enqueue for testing dispatch before ISE-triggered enqueueing exists.")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Body of {@code POST /api/v1/jobs}.
     *
     * @param endpointId the endpoint to run against (required)
     * @param jobType    what to run; defaults to {@code POSTURE_CHECK} when omitted
     * @param priority   higher values are claimed first; defaults to 0
     */
    public record EnqueueRequest(@NotNull UUID endpointId, JobType jobType, Integer priority) {}

    /**
     * Manually enqueues a job.
     *
     * @return the new job, status {@code QUEUED}
     */
    @Operation(summary = "Manually enqueue a job for an endpoint (testing only, until ISE-triggered enqueueing exists)")
    @PostMapping
    public JobResponse enqueue(@Valid @RequestBody EnqueueRequest request) {
        PostureJob job = jobService.enqueue(
                request.endpointId(),
                request.jobType() != null ? request.jobType() : JobType.POSTURE_CHECK,
                request.priority() != null ? request.priority() : 0
        );
        return toResponse(job);
    }

    /** @return all jobs, newest first */
    @Operation(summary = "List all jobs, newest first")
    @GetMapping
    public List<JobResponse> listAll() {
        return jobService.listAll().stream().map(this::toResponse).toList();
    }

    /**
     * @param endpointId the endpoint's internal UUID
     * @return that endpoint's jobs, newest first
     */
    @Operation(summary = "List jobs for one endpoint")
    @GetMapping("/endpoint/{endpointId}")
    public List<JobResponse> listForEndpoint(@PathVariable UUID endpointId) {
        return jobService.listForEndpoint(endpointId).stream().map(this::toResponse).toList();
    }

    private JobResponse toResponse(PostureJob job) {
        return new JobResponse(
                job.getId(),
                job.getEndpoint().getId(),
                job.getEndpoint().getMacAddress(),
                job.getJobType().name(),
                job.getStatus().name(),
                job.getPriority(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }
}
