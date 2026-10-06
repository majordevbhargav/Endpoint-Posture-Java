package com.endpointposture.posture;

import com.endpointposture.posture.dto.AssessmentResponse;
import com.endpointposture.posture.dto.CheckInput;
import com.endpointposture.posture.dto.CheckResultResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

/**
 * Reads and writes posture evidence ({@link Assessment} and {@link CheckResult}).
 *
 * <p>This is the single write path for assessments. It is called by
 * {@link PostureIngestService} when an agent submits a report, and by
 * {@link com.endpointposture.job.JobWorker} (through {@link #recordFailure})
 * when a check fails before a report could be submitted.</p>
 *
 * <p>Reads that return many assessments load their checks in batches
 * ({@link #CHECK_BATCH_SIZE} ids per query) instead of one query per assessment.</p>
 */
@Service
public class AssessmentService {

    /**
     * Assessment ids per {@code IN (...)} query. Postgres allows about 32k bind
     * parameters per statement, so a fleet-wide read must be split into chunks.
     */
    static final int CHECK_BATCH_SIZE = 1000;

    private final AssessmentRepository assessmentRepository;
    private final CheckResultRepository checkResultRepository;

    public AssessmentService(AssessmentRepository assessmentRepository,
                              CheckResultRepository checkResultRepository) {
        this.assessmentRepository = assessmentRepository;
        this.checkResultRepository = checkResultRepository;
    }

    /**
     * The single write path for posture evidence. Always inserts a new row -
     * never updates a prior assessment. No ISE call happens in this method.
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

        assessmentRepository.updateEndpointLatest(
                endpointId,
                assessment.getId(),
                assessment.getStatus().name(),
                assessment.getCreatedAt() != null ? assessment.getCreatedAt() : Instant.now()
        );

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
     * report. Writes an {@code ERROR} assessment with no checks: a failed
     * attempt is still evidence.
     */
    @Transactional
    public void recordFailure(UUID endpointId, UUID jobId, String detail) {
        Instant now = Instant.now();
        recordAssessment(endpointId, jobId, now, now, AssessmentStatus.ERROR, detail, List.of());
    }

    /** @return all of an endpoint's assessments, newest first (empty list if none); checks loaded in one batch */
    @Transactional(readOnly = true)
    public List<AssessmentResponse> getHistoryForEndpoint(UUID endpointId) {
        return toResponses(assessmentRepository.findByEndpointIdOrderByCreatedAtDesc(endpointId));
    }

    /**
     * @return an endpoint's most recent assessment, or empty if it has never been assessed
     */
    @Transactional(readOnly = true)
    public Optional<AssessmentResponse> findLatestForEndpoint(UUID endpointId) {
        return assessmentRepository.findFirstByEndpointIdOrderByCreatedAtDesc(endpointId)
                .map(this::toResponse);
    }

    /**
     * @return an endpoint's most recent assessment
     * @throws AssessmentNotFoundException if it has never been assessed
     */
    @Transactional(readOnly = true)
    public AssessmentResponse getLatestForEndpoint(UUID endpointId) {
        return findLatestForEndpoint(endpointId)
                .orElseThrow(() -> new AssessmentNotFoundException(endpointId.toString()));
    }

    /** @return the newest assessment for every endpoint that has one; endpoints never assessed are simply absent */
    @Transactional(readOnly = true)
    public List<AssessmentResponse> getLatestForAllEndpoints() {
        return toResponses(assessmentRepository.findLatestPerEndpoint());
    }
    /** Latest assessment with checks for a handful of endpoints (one page on screen). */
    @Transactional(readOnly = true)
    public List<AssessmentResponse> getLatestForEndpoints(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return toResponses(assessmentRepository.findLatestForEndpoints(ids));
    }

    /** Single assessment: one query for its checks. */
    private AssessmentResponse toResponse(Assessment a) {
        List<CheckResultResponse> checks = checkResultRepository.findByAssessmentId(a.getId())
                .stream()
                .map(this::toCheckResponse)
                .toList();
        return build(a, checks);
    }

    /**
     * Many assessments: loads every check for them in
     * {@code ceil(n / CHECK_BATCH_SIZE)} queries instead of {@code n}.
     */
    private List<AssessmentResponse> toResponses(List<Assessment> assessments) {
        if (assessments.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = assessments.stream().map(Assessment::getId).toList();
        Map<UUID, List<CheckResultResponse>> checksByAssessment = new HashMap<>();

        for (int from = 0; from < ids.size(); from += CHECK_BATCH_SIZE) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + CHECK_BATCH_SIZE));
            for (CheckResult c : checkResultRepository.findByAssessmentIdIn(chunk)) {
                checksByAssessment
                        .computeIfAbsent(c.getAssessmentId(), k -> new ArrayList<>())
                        .add(toCheckResponse(c));
            }
        }

        return assessments.stream()
                .map(a -> build(a, checksByAssessment.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    private CheckResultResponse toCheckResponse(CheckResult c) {
        return new CheckResultResponse(
                c.getId(), c.getCheckType(), c.getStatus(), c.getDetails(), c.getCreatedAt());
    }

    private AssessmentResponse build(Assessment a, List<CheckResultResponse> checks) {
        return new AssessmentResponse(
                a.getId(), a.getEndpointId(), a.getJobId(), a.getStatus(),
                a.getDetail(), a.getStartedAt(), a.getCompletedAt(), checks
        );
    }
}