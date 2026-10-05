package com.endpointposture.hardware;

import com.endpointposture.hardware.HardwareHealthService.RecommendationInput;
import com.endpointposture.hardware.dto.HardwareHealthResponse;
import com.endpointposture.warranty.WarrantyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HardwareHealthServiceReportTest {

    @Mock HardwareHealthRepository healthRepo;
    @Mock HardwareRecommendationRepository recoRepo;
    @Mock WarrantyService warrantyService;
    HardwareHealthService service;

    final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HardwareHealthService(healthRepo, recoRepo, warrantyService);
        when(healthRepo.save(any())).thenAnswer(i -> {
            HardwareHealthReport r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(recoRepo.findByHardwareHealthId(any())).thenReturn(List.of());
        when(warrantyService.describe(any())).thenReturn(null);
    }

    private HardwareHealthResponse record(int cpu, int mem, int sto, Integer bat, List<RecommendationInput> recs) {
        return service.recordReport(endpointId, null, "Dell", "Latitude", "SN1", "1.0",
                cpu, mem, sto, bat, 3, "UNKNOWN", null, Map.of(), Instant.now(), recs);
    }

    private HardwareHealthReport report(boolean ok, Instant at) {
        return HardwareHealthReport.builder().id(UUID.randomUUID()).endpointId(endpointId)
                .succeeded(ok).errorMessage(ok ? null : "winrm down")
                .cpuScore(ok ? 90 : null).memoryScore(ok ? 90 : null).storageScore(ok ? 90 : null)
                .overallScore(ok ? 90 : null).overallBand(ok ? HardwareBand.HEALTHY : null)
                .rawReport(Map.of()).collectedAt(at).build();
    }

    // ---- scoring ----
    @Test
    void overallIsTheAverageOfThreeWhenThereIsNoBattery() {
        HardwareHealthResponse r = record(80, 60, 100, null, List.of());
        assertEquals(80, r.overallScore());
        assertEquals(HardwareBand.WARNING, r.overallBand());
        assertNull(r.batteryScore());
    }

    @Test
    void overallIncludesTheBatteryWhenPresent() {
        HardwareHealthResponse r = record(80, 60, 100, 20, List.of());   // 260 / 4 = 65
        assertEquals(65, r.overallScore());
        assertEquals(HardwareBand.DEGRADED, r.overallBand());
    }

    @Test
    void aZeroBatteryScoreIsNotTreatedAsMissing() {
        HardwareHealthResponse r = record(100, 100, 100, 0, List.of());  // 300 / 4 = 75
        assertEquals(75, r.overallScore());
    }

    @Test
    void successfulReportIsMarkedSucceededWithNoError() {
        HardwareHealthResponse r = record(100, 100, 100, null, List.of());
        assertTrue(r.succeeded());
        assertNull(r.errorMessage());
        assertEquals(HardwareBand.HEALTHY, r.overallBand());
    }

    // ---- recommendations ----
    @Test
    void malformedRecommendationsAreSkippedAndBadPriorityBecomesMedium() {
        record(100, 100, 100, null, List.of(
                new RecommendationInput("HIGH", "Storage", "Replace disk"),
                new RecommendationInput("urgent!!", "CPU", "Check load"),
                new RecommendationInput(null, "Memory", "Add RAM"),
                new RecommendationInput("LOW", null, "no area"),
                new RecommendationInput("LOW", "Battery", null)));

        ArgumentCaptor<HardwareRecommendation> c = ArgumentCaptor.forClass(HardwareRecommendation.class);
        verify(recoRepo, times(3)).save(c.capture());
        assertEquals(HardwareRecommendation.RecommendationPriority.HIGH, c.getAllValues().get(0).getPriority());
        assertEquals(HardwareRecommendation.RecommendationPriority.MEDIUM, c.getAllValues().get(1).getPriority());
        assertEquals(HardwareRecommendation.RecommendationPriority.MEDIUM, c.getAllValues().get(2).getPriority());
    }

    @Test
    void priorityParsingIsCaseInsensitive() {
        record(100, 100, 100, null, List.of(new RecommendationInput(" high ", "Storage", "x")));
        ArgumentCaptor<HardwareRecommendation> c = ArgumentCaptor.forClass(HardwareRecommendation.class);
        verify(recoRepo).save(c.capture());
        assertEquals(HardwareRecommendation.RecommendationPriority.HIGH, c.getValue().getPriority());
    }

    // ---- warranty override ----
    @Test
    void liveWarrantyBeatsTheStoredValueOnRead() {
        when(warrantyService.describe("SN1")).thenReturn(new WarrantyService.Info("EXPIRING_SOON", 12));
        HardwareHealthResponse r = record(100, 100, 100, null, List.of());
        assertEquals("EXPIRING_SOON", r.warrantyStatus());
        assertEquals(12, r.warrantyDaysRemaining());
    }

    @Test
    void storedWarrantyIsTheFallbackWhenNoRecordExists() {
        HardwareHealthResponse r = record(100, 100, 100, null, List.of());
        assertEquals("UNKNOWN", r.warrantyStatus());
    }

    // ---- failure rows ----
    @Test
    void recordFailureStoresNullScoresNeverZero() {
        HardwareHealthResponse r = service.recordFailure(endpointId, null, "timed out");
        assertFalse(r.succeeded());
        assertNull(r.cpuScore());
        assertNull(r.overallScore());
        assertNull(r.overallBand());
        assertEquals("timed out", r.errorMessage());
    }

    @Test
    void recordFailureWithNoReasonUsesADefault() {
        assertEquals("Unknown failure", service.recordFailure(endpointId, null, null).errorMessage());
    }

    // ---- latest ----
    @Test
    void latestWithNoRowsThrowsNotFound() {
        when(healthRepo.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId)).thenReturn(Optional.empty());
        assertThrows(HardwareHealthNotFoundException.class, () -> service.getLatestForEndpoint(endpointId));
    }

    @Test
    void latestReturnsTheNewestRunWhenItSucceeded() {
        HardwareHealthReport good = report(true, Instant.now());
        when(healthRepo.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId)).thenReturn(Optional.of(good));
        HardwareHealthResponse r = service.getLatestForEndpoint(endpointId);
        assertEquals(good.getId(), r.id());
        assertNull(r.lastAttemptFailedAt());
    }

    @Test
    void latestShowsTheLastGoodRunWithAWarningWhenTheNewestFailed() {
        Instant now = Instant.now();
        HardwareHealthReport failed = report(false, now);
        HardwareHealthReport good = report(true, now.minusSeconds(3600));
        when(healthRepo.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId)).thenReturn(Optional.of(failed));
        when(healthRepo.findFirstByEndpointIdAndSucceededTrueOrderByCollectedAtDesc(endpointId)).thenReturn(Optional.of(good));

        HardwareHealthResponse r = service.getLatestForEndpoint(endpointId);

        assertEquals(good.getId(), r.id());
        assertTrue(r.succeeded());
        assertEquals(now, r.lastAttemptFailedAt());
        assertEquals("winrm down", r.lastAttemptError());
    }

    @Test
    void latestReturnsTheFailedRowWhenNothingEverSucceeded() {
        HardwareHealthReport failed = report(false, Instant.now());
        when(healthRepo.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId)).thenReturn(Optional.of(failed));
        when(healthRepo.findFirstByEndpointIdAndSucceededTrueOrderByCollectedAtDesc(endpointId)).thenReturn(Optional.empty());

        HardwareHealthResponse r = service.getLatestForEndpoint(endpointId);

        assertFalse(r.succeeded());
        assertNull(r.overallScore());
        assertNull(r.lastAttemptFailedAt());
    }

    @Test
    void fleetLatestPairsEachFailedEndpointWithItsOwnLastGoodRun() {
        UUID other = UUID.randomUUID();
        HardwareHealthReport failedA = report(false, Instant.now());
        HardwareHealthReport goodA = report(true, Instant.now().minusSeconds(60));
        HardwareHealthReport okB = report(true, Instant.now());
        okB.setEndpointId(other);
        when(healthRepo.findLatestPerEndpoint()).thenReturn(List.of(failedA, okB));
        when(healthRepo.findLatestSuccessfulPerEndpoint()).thenReturn(List.of(goodA, okB));

        List<HardwareHealthResponse> out = service.getLatestForAllEndpoints();

        assertEquals(2, out.size());
        assertEquals(goodA.getId(), out.get(0).id());
        assertNotNull(out.get(0).lastAttemptFailedAt());
        assertEquals(okB.getId(), out.get(1).id());
        assertNull(out.get(1).lastAttemptFailedAt());
    }
}