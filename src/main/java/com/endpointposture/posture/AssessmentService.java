package com.endpointposture.posture;

import com.endpointposture.posture.dto.AssessmentResponse;
import com.endpointposture.posture.dto.CheckInput;
import com.endpointposture.posture.dto.CheckResultResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes posture evidence ({@link Assessment} and {@link CheckResult}).
 *
 * <p>This is the single write path for assessments. It is called by
 * {@link PostureIngestService} when an agent submits a report, and by
 * {@link com.endpointposture.job.JobWorker} (through {@link #recordFailure})
 * when a check fails before a report could be submitted.</p>
 */
@Service
public class AssessmentService {

    private final AssessmentRepository assessmentRepository;
    private final CheckResultRepository checkResultRepository;

    public AssessmentService(AssessmentRepository assessmentRepository,
                              CheckResultRepository checkResultRepository) {
        this.assessmentRepository = assessmentRepository;
        this.checkResultRepository = checkResultRepository;
    }

    /**
     * The single write path for posture evidence. Called once a finished
     * result exists (from the HTTP ingestion endpoint). Always inserts a
     * new row - never updates a prior assessment - per the append-only
     * decision behind the whole OBSERVATION -> EVIDENCE model.
     *
     * <p>No ISE call happens anywhere in this method, deliberately.</p>
     *
     * @param endpointId    the endpoint that was assessed
     * @param jobId         the triggering job, or {@code null} if none
     * @param startedAt     when collection began
     * @param completedAt   when the assessment was finished
     * @param overallStatus overall result across all checks
     * @param detail        short human-readable summary
     * @param checks        the individual check results, saved together with the assessment
     * @return the saved assessment with its checks
     */
    @Transactional
    public AssessmentResponse recordAssessment(
            UUID endpointId,
            UUID jobId,
            Instant startedAt,
            Instant completedAt,
            AssessmentStatus overallStatus,
            String detail,
            List<CheckInput> checks
    ) {
        Assessment assessment = Assessment.builder()
                .endpointId(endpointId)
                .jobId(jobId)
                .status(overallStatus)
                .detail(detail)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .build();

        assessment = assessmentRepository.save(assessment);

        UUID assessmentId = assessment.getId();
        for (CheckInput check : checks) {
            CheckResult result = CheckResult.builder()
                    .assessmentId(assessmentId)
                    .checkType(check.checkType())
                    .status(check.status())
                    .details(check.details())
                    .build();
            checkResultRepository.save(result);
        }

        return toResponse(assessment);
    }

    /**
     * Records that a posture check was attempted but did not produce a
     * report (timeout, crash, unreachable endpoint, failed submission).
     * Writes an {@code ERROR} assessment with no checks: a failed attempt is
     * still evidence that a check was tried, so it is kept rather than dropped.
     *
     * @param endpointId the endpoint that was being checked
     * @param jobId      the job that made the attempt
     * @param detail     what went wrong
     */
    @Transactional
    public void recordFailure(UUID endpointId, UUID jobId, String detail) {
        Instant now = Instant.now();
        recordAssessment(endpointId, jobId, now, now, AssessmentStatus.ERROR, detail, List.of());
    }

    /**
     * @param endpointId the endpoint's internal UUID
     * @return all of its assessments, newest first (empty list if none)
     */
    @Transactional(readOnly = true)
    public List<AssessmentResponse> getHistoryForEndpoint(UUID endpointId) {
        return assessmentRepository.findByEndpointIdOrderByCreatedAtDesc(endpointId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * @param endpointId the endpoint's internal UUID
     * @return its most recent assessment
     * @throws AssessmentNotFoundException if it has never been assessed
     */
    @Transactional(readOnly = true)
    public AssessmentResponse getLatestForEndpoint(UUID endpointId) {
        return assessmentRepository.findFirstByEndpointIdOrderByCreatedAtDesc(endpointId)
                .map(this::toResponse)
                .orElseThrow(() -> new AssessmentNotFoundException(endpointId.toString()));
    }

    private AssessmentResponse toResponse(Assessment a) {
        List<CheckResultResponse> checks = checkResultRepository.findByAssessmentId(a.getId())
                .stream()
                .map(c -> new CheckResultResponse(
                        c.getId(), c.getCheckType(), c.getStatus(), c.getDetails(), c.getCreatedAt()))
                .toList();

        return new AssessmentResponse(
                a.getId(), a.getEndpointId(), a.getJobId(), a.getStatus(),
                a.getDetail(), a.getStartedAt(), a.getCompletedAt(), checks
        );
    }
}
