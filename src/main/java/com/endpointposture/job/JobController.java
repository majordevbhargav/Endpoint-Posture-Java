package com.endpointposture.job;

import com.endpointposture.job.dto.JobResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "The posture-check job queue. Stage 3: manual enqueue for testing the claim/complete/fail mechanics before real ISE/PowerShell dispatch exists.")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    public record EnqueueRequest(@NotNull UUID endpointId, Integer priority) {}

    @Operation(summary = "Manually enqueue a posture-check job for an endpoint (testing only, until Stage 4 wires real ISE-triggered enqueueing)")
    @PostMapping
    public JobResponse enqueue(@RequestBody EnqueueRequest request) {
        PostureJob job = jobService.enqueue(
                request.endpointId(),
                JobType.POSTURE_CHECK,
                request.priority() != null ? request.priority() : 0
        );
        return toResponse(job);
    }

    @Operation(summary = "List all jobs, newest first")
    @GetMapping
    public List<JobResponse> listAll() {
        return jobService.listAll().stream().map(this::toResponse).toList();
    }

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