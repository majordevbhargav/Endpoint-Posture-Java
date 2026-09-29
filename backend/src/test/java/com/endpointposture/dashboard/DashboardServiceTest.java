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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    EndpointRepository endpoints;
    @Mock
    AssessmentRepository assessments;
    @Mock
    DashboardRepository dashboard;

    private Assessment a(AssessmentStatus s, Duration age) {
        return Assessment.builder().status(s).createdAt(Instant.now().minus(age)).build();
    }

    private Assessment a(UUID endpointId, AssessmentStatus s, Duration age) {
        return Assessment.builder().endpointId(endpointId).status(s).createdAt(Instant.now().minus(age)).build();
    }

    @Test
    void summaryCountsOnlyConnectedEndpointsForPostureBuckets() {
        DashboardService service = new DashboardService(endpoints, assessments, dashboard, 4);
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), offline = UUID.randomUUID();
        when(endpoints.count()).thenReturn(5L);
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(
                Endpoint.builder().id(c1).build(), Endpoint.builder().id(c2).build()));
        when(assessments.findLatestPerEndpoint()).thenReturn(List.of(
                a(c1, AssessmentStatus.COMPLIANT, Duration.ofHours(10)), // connected, stale
                a(c2, AssessmentStatus.NON_COMPLIANT, Duration.ofHours(1)),
                a(offline, AssessmentStatus.ERROR, Duration.ofHours(1)))); // offline: excluded

        Summary s = service.summary();

        assertEquals(5, s.total());
        assertEquals(2, s.connected());
        assertEquals(3, s.notConnected());
        assertEquals(1, s.compliant());
        assertEquals(1, s.nonCompliant());
        assertEquals(0, s.error());
        assertEquals(0, s.unassessed());
        assertEquals(1, s.stale());
    }
}