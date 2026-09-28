package com.endpointposture.dashboard;

import com.endpointposture.dashboard.DashboardDtos.Summary;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.posture.Assessment;
import com.endpointposture.posture.AssessmentRepository;
import com.endpointposture.posture.AssessmentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock EndpointRepository endpoints;
    @Mock AssessmentRepository assessments;
    @Mock DashboardRepository dashboard;

    private Assessment a(AssessmentStatus s, Duration age) {
        return Assessment.builder().status(s).createdAt(Instant.now().minus(age)).build();
    }

    @Test
    void summaryCountsEachBucketAndStaleAndUnassessed() {
        DashboardService service = new DashboardService(endpoints, assessments, dashboard, 4); // stale after 8h
        when(endpoints.count()).thenReturn(7L);
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(Endpoint.builder().build(), Endpoint.builder().build()));
        when(assessments.findLatestPerEndpoint()).thenReturn(List.of(
                a(AssessmentStatus.COMPLIANT, Duration.ofHours(1)),
                a(AssessmentStatus.COMPLIANT, Duration.ofHours(10)),   // stale
                a(AssessmentStatus.NON_COMPLIANT, Duration.ofHours(2)),
                a(AssessmentStatus.ERROR, Duration.ofHours(3))));

        Summary s = service.summary();

        assertEquals(7, s.total());
        assertEquals(2, s.connected());
        assertEquals(5, s.notConnected());
        assertEquals(2, s.compliant());
        assertEquals(1, s.nonCompliant());
        assertEquals(1, s.error());
        assertEquals(3, s.unassessed());
        assertEquals(1, s.stale());
    }
}