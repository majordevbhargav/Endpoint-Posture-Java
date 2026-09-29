package com.endpointposture.hardware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Table-driven test for HardwareHealthService.bandFor's thresholds
 * (85/70/50, currently illustrative per the class Javadoc). This test
 * documents current behavior so a future threshold change is a
 * deliberate, visible diff here, not a silent drift.
 */
@ExtendWith(MockitoExtension.class)
class HardwareHealthServiceBandForTest {

    @Mock
    HardwareHealthRepository healthRepository;
    @Mock
    HardwareRecommendationRepository recommendationRepository;

    private HardwareHealthService service;

    @BeforeEach
    void setUp() {
        service = new HardwareHealthService(healthRepository, recommendationRepository);
    }

    @Test
    void topOfRangeAndPerfectScoreAreHealthy() {
        assertEquals(HardwareBand.HEALTHY, service.bandFor(85));
        assertEquals(HardwareBand.HEALTHY, service.bandFor(100));
    }

    @Test
    void justBelowHealthyThresholdIsWarning() {
        assertEquals(HardwareBand.WARNING, service.bandFor(84));
    }

    @Test
    void warningRangeBoundaries() {
        assertEquals(HardwareBand.WARNING, service.bandFor(70));
        assertEquals(HardwareBand.WARNING, service.bandFor(84));
    }

    @Test
    void justBelowWarningThresholdIsDegraded() {
        assertEquals(HardwareBand.DEGRADED, service.bandFor(69));
    }

    @Test
    void degradedRangeBoundaries() {
        assertEquals(HardwareBand.DEGRADED, service.bandFor(50));
        assertEquals(HardwareBand.DEGRADED, service.bandFor(69));
    }

    @Test
    void justBelowDegradedThresholdIsCritical() {
        assertEquals(HardwareBand.CRITICAL, service.bandFor(49));
    }

    @Test
    void zeroAndNegativeEdgeAreCritical() {
        assertEquals(HardwareBand.CRITICAL, service.bandFor(0));
    }
}