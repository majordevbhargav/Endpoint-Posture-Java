package com.endpointposture.posture;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.posture.dto.AssessmentResponse;
import com.endpointposture.posture.dto.CheckInput;
import com.endpointposture.posture.dto.PostureReportRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns a posture report from an agent into stored evidence.
 *
 * <p>One report becomes, in a single transaction: the endpoint found or
 * created by MAC (plus its hardware identity), one {@link Assessment}, and
 * one {@link CheckResult} per check. If any step fails, nothing is saved.</p>
 *
 * <p><b>Non-negotiable rule:</b> nothing in this class calls Cisco ISE.
 * Ingesting a posture result never triggers share, restrict or clear - those
 * are separate, explicit operator actions.</p>
 */
@Service
public class PostureIngestService {

    private final EndpointService endpointService;
    private final AssessmentService assessmentService;

    public PostureIngestService(EndpointService endpointService, AssessmentService assessmentService) {
        this.endpointService = endpointService;
        this.assessmentService = assessmentService;
    }

    /**
     * Records one agent report.
     *
     * @param req the validated report
     * @return the saved assessment with its checks
     */
    @Transactional
    public AssessmentResponse ingest(PostureReportRequest req) {
        Endpoint endpoint = endpointService.upsertByMac(
                req.macAddress(), req.ipAddress(), req.hostname(), req.osName(), req.osVersion());

        if (req.hardware() != null) {
            endpointService.updateHardware(endpoint.getId(),
                    req.hardware().manufacturer(), req.hardware().model(), req.hardware().serialNumber());
        }

        List<CheckInput> checks = req.checks() == null ? List.of() : req.checks();
        Instant completedAt = Instant.now();
        Instant startedAt = req.collectedAt() != null ? req.collectedAt() : completedAt;

        return assessmentService.recordAssessment(
                endpoint.getId(),
                parseUuidOrNull(req.jobId()),
                startedAt,
                completedAt,
                overallStatus(req.status(), checks),
                summarize(checks),
                checks);
    }

    /**
     * Combines the agent's reported overall status with the individual
     * checks and returns the most severe one, so the stored overall status
     * can never look better than its own checks. Relies on the severity
     * order of {@link AssessmentStatus}.
     */
    private AssessmentStatus overallStatus(AssessmentStatus reported, List<CheckInput> checks) {
        AssessmentStatus worst = reported;
        for (CheckInput check : checks) {
            if (check.status().ordinal() > worst.ordinal()) {
                worst = check.status();
            }
        }
        return worst;
    }

    /** One line per non-compliant check, joined with " | ", using the check's own summary when it provides one. */
    private String summarize(List<CheckInput> checks) {
        if (checks.isEmpty()) return "No checks reported";

        List<String> problems = checks.stream()
                .filter(c -> c.status() != AssessmentStatus.COMPLIANT)
                .map(c -> {
                    Object summary = c.details() == null ? null : c.details().get("summary");
                    return c.checkType() + ": " + (summary != null ? summary : c.status());
                })
                .toList();

        return problems.isEmpty() ? "All checks compliant" : String.join(" | ", problems);
    }

    /** The agent sends the job ID as text; a missing or malformed value simply means "no linked job". */
    private UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
