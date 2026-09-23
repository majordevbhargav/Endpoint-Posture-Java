package com.endpointposture.hardware;

import com.endpointposture.hardware.dto.HardwareHealthResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes {@link HardwareHealthReport} + {@link HardwareRecommendation}.
 * The single persistence path — called by {@link HardwareIngestService} once
 * scoring succeeds, or by {@link com.endpointposture.job.JobWorker} via
 * {@link #recordFailure} when a hardware job fails before a report could be
 * produced.
 */
@Service
public class HardwareHealthService {

    // Illustrative bands, per project plan Section 15 question 10 —
    // not yet confirmed against real fleet data. Change here only.
    private static final int HEALTHY_THRESHOLD = 85;
    private static final int WARNING_THRESHOLD = 70;
    private static final int DEGRADED_THRESHOLD = 50;

    private final HardwareHealthRepository healthRepository;
    private final HardwareRecommendationRepository recommendationRepository;

    public HardwareHealthService(HardwareHealthRepository healthRepository,
                                  HardwareRecommendationRepository recommendationRepository) {
        this.healthRepository = healthRepository;
        this.recommendationRepository = recommendationRepository;
    }

    public record RecommendationInput(String priority, String area, String action) {}

    @Transactional
    public HardwareHealthResponse recordReport(
            UUID endpointId, UUID jobId,
            String manufacturer, String model, String serialNumber, String biosVersion,
            int cpuScore, int memoryScore, int storageScore, Integer batteryScore,
            Integer hardwareEventCount, String warrantyStatus, Integer warrantyDaysRemaining,
            Map<String, Object> rawReport, Instant collectedAt,
            List<RecommendationInput> recommendations
    ) {
        // Overall = simple average of the components that actually apply.
        // Battery is only folded in when present — averaging in a phantom
        // 0 for every battery-less desktop would be wrong, not just
        // slightly off, per BatteryScorer's own contract.
        int componentSum = cpuScore + memoryScore + storageScore;
        int componentCount = 3;
        if (batteryScore != null) {
            componentSum += batteryScore;
            componentCount++;
        }
        int overallScore = Math.round(componentSum / (float) componentCount);
        HardwareBand band = bandFor(overallScore);

        HardwareHealthReport report = HardwareHealthReport.builder()
                .endpointId(endpointId)
                .jobId(jobId)
                .manufacturer(manufacturer)
                .model(model)
                .serialNumber(serialNumber)
                .biosVersion(biosVersion)
                .cpuScore(cpuScore)
                .memoryScore(memoryScore)
                .storageScore(storageScore)
                .batteryScore(batteryScore)
                .overallScore(overallScore)
                .overallBand(band)
                .hardwareEventCount(hardwareEventCount)
                .warrantyStatus(warrantyStatus)
                .warrantyDaysRemaining(warrantyDaysRemaining)
                .rawReport(rawReport)
                .collectedAt(collectedAt)
                .succeeded(true)
                .errorMessage(null)
                .build();

        report = healthRepository.save(report);

        UUID reportId = report.getId();
        for (RecommendationInput r : recommendations) {
            if (r.area() == null || r.action() == null) continue; // skip malformed entries silently
            HardwareRecommendation.RecommendationPriority priority = parsePriority(r.priority());
            recommendationRepository.save(HardwareRecommendation.builder()
                    .hardwareHealthId(reportId)
                    .priority(priority)
                    .area(r.area())
                    .action(r.action())
                    .build());
        }

        return toResponse(report);
    }

    /**
     * Records that a hardware-health check was attempted but never produced
     * a scoreable report (timeout, crash, unreachable endpoint, collection
     * failure, or a failed submission). Writes a row with
     * {@code succeeded = false} and every score field {@code null} — never
     * zero, since a zero score would read as a genuinely critical hardware
     * reading rather than "we couldn't check." This is the hardware-side
     * equivalent of {@link com.endpointposture.posture.AssessmentService#recordFailure}.
     *
     * @param endpointId the endpoint that was being checked
     * @param jobId      the job that made the attempt
     * @param reason     what went wrong
     */
    @Transactional
    public HardwareHealthResponse recordFailure(UUID endpointId, UUID jobId, String reason) {
        String detail = reason == null ? "Unknown failure" : reason;

        HardwareHealthReport report = HardwareHealthReport.builder()
                .endpointId(endpointId)
                .jobId(jobId)
                .succeeded(false)
                .errorMessage(detail)
                .rawReport(Map.of("error", detail))
                .collectedAt(Instant.now())
                .build();

        report = healthRepository.save(report);
        return toResponse(report);
    }

    @Transactional(readOnly = true)
    public List<HardwareHealthResponse> getHistoryForEndpoint(UUID endpointId) {
        return healthRepository.findByEndpointIdOrderByCollectedAtDesc(endpointId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public HardwareHealthResponse getLatestForEndpoint(UUID endpointId) {
        return healthRepository.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId)
                .map(this::toResponse)
                .orElseThrow(() -> new HardwareHealthNotFoundException(endpointId.toString()));
    }

    HardwareBand bandFor(int score) {
        if (score >= HEALTHY_THRESHOLD) return HardwareBand.HEALTHY;
        if (score >= WARNING_THRESHOLD) return HardwareBand.WARNING;
        if (score >= DEGRADED_THRESHOLD) return HardwareBand.DEGRADED;
        return HardwareBand.CRITICAL;
    }

    private HardwareRecommendation.RecommendationPriority parsePriority(String raw) {
        if (raw == null) return HardwareRecommendation.RecommendationPriority.MEDIUM;
        try {
            return HardwareRecommendation.RecommendationPriority.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return HardwareRecommendation.RecommendationPriority.MEDIUM;
        }
    }

    private HardwareHealthResponse toResponse(HardwareHealthReport r) {
        List<HardwareHealthResponse.RecommendationDto> recs =
                recommendationRepository.findByHardwareHealthId(r.getId()).stream()
                        .map(rec -> new HardwareHealthResponse.RecommendationDto(
                                rec.getPriority().name(), rec.getArea(), rec.getAction()))
                        .toList();

        return new HardwareHealthResponse(
                r.getId(), r.getEndpointId(), r.getJobId(),
                r.getManufacturer(), r.getModel(), r.getSerialNumber(), r.getBiosVersion(),
                r.getCpuScore(), r.getMemoryScore(), r.getStorageScore(), r.getBatteryScore(),
                r.getOverallScore(), r.getOverallBand(),
                r.getHardwareEventCount(), r.getWarrantyStatus(), r.getWarrantyDaysRemaining(),
                r.getCollectedAt(), r.isSucceeded(), r.getErrorMessage(), recs
        );
    }
}