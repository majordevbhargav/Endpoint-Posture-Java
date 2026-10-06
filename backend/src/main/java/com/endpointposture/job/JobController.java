package com.endpointposture.job;

import com.endpointposture.job.dto.JobResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for the job queue: {@code /api/v1/jobs}.
 *
 * <p>Manual jobs default to priority 10 so they run ahead of automatic
 * (priority 0) rechecks. List endpoints are always bounded by a {@code limit}
 * because the table grows continuously (old finished jobs are pruned nightly
 * by {@link JobRetentionScheduler}).</p>
 */
@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "The posture/hardware job queue.")
public class JobController {

    /** Priority given to jobs enqueued by a person. Automatic jobs use 0. */
    private static final int MANUAL_PRIORITY = 10;

    /** Rows returned by {@code GET /jobs} when the caller gives no limit. */
    private static final int DEFAULT_LIST_LIMIT = 200;
    /** Hard ceiling for any list limit, whatever the caller asks for. */
    private static final int MAX_LIST_LIMIT = 1000;
    /** Rows returned by {@code GET /jobs/endpoint/{id}} when the caller gives no limit. */
    private static final int DEFAULT_ENDPOINT_LIMIT = 100;

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Body of {@code POST /api/v1/jobs}.
     *
     * @param endpointId the endpoint to run against (required)
     * @param jobType    what to run; defaults to {@code POSTURE_CHECK}
     * @param priority   higher values are claimed first; defaults to 10 (manual)
     */
    public record EnqueueRequest(@NotNull UUID endpointId, JobType jobType, Integer priority) {}

    public record BulkEnqueueRequest(@NotNull JobType jobType) {}

    @Operation(summary = "Manually enqueue a job for an endpoint")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ANALYST')")
    @PostMapping
    public JobResponse enqueue(@Valid @RequestBody EnqueueRequest request) {
        PostureJob job = jobService.enqueue(
                request.endpointId(),
                request.jobType() != null ? request.jobType() : JobType.POSTURE_CHECK,
                request.priority() != null ? request.priority() : MANUAL_PRIORITY
        );
        return toResponse(job);
    }

    @Operation(summary = "Enqueue a job for every connected endpoint (one statement)")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ANALYST')")
    @PostMapping("/bulk")
    public Map<String, Integer> enqueueBulk(@Valid @RequestBody BulkEnqueueRequest request) {
        return Map.of("queued", jobService.enqueueForConnected(request.jobType(), MANUAL_PRIORITY));
    }

    @Operation(summary = "List the newest jobs",
            description = "Newest first, at most 'limit' rows (default 200, maximum 1000). "
                    + "Use /api/v1/system/health for queue totals.")
    @GetMapping
    public List<JobResponse> listAll(@RequestParam(defaultValue = "" + DEFAULT_LIST_LIMIT) int limit) {
        return jobService.listRecent(clamp(limit)).stream().map(this::toResponse).toList();
    }

    @Operation(summary = "List the newest jobs for one endpoint",
            description = "Newest first, at most 'limit' rows (default 100, maximum 1000).")
    @GetMapping("/endpoint/{endpointId}")
    public List<JobResponse> listForEndpoint(@PathVariable UUID endpointId,
                                             @RequestParam(defaultValue = "" + DEFAULT_ENDPOINT_LIMIT) int limit) {
        return jobService.listForEndpoint(endpointId, clamp(limit)).stream().map(this::toResponse).toList();
    }

    /** Keeps a caller-supplied limit between 1 and {@link #MAX_LIST_LIMIT}. */
    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
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