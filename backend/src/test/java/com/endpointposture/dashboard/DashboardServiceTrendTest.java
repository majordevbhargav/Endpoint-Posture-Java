package com.endpointposture.dashboard;

import com.endpointposture.dashboard.DashboardDtos.CategoryRate;
import com.endpointposture.dashboard.DashboardDtos.Summary;
import com.endpointposture.dashboard.DashboardDtos.TrendPoint;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.posture.Assessment;
import com.endpointposture.posture.AssessmentRepository;
import com.endpointposture.posture.AssessmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTrendTest {

    @Mock
    EndpointRepository endpoints;
    @Mock
    AssessmentRepository assessments;
    @Mock
    DashboardRepository dashboard;
    DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(endpoints, assessments, dashboard, 4);
    }

    private DashboardRepository.TrendRow trend(String day, long assessed, long compliant) {
        DashboardRepository.TrendRow r = mock(DashboardRepository.TrendRow.class);
        when(r.getDay()).thenReturn(day);
        when(r.getAssessed()).thenReturn(assessed);
        when(r.getCompliant()).thenReturn(compliant);
        return r;
    }

    private DashboardRepository.CategoryRow cat(String type, long total, long passing) {
        DashboardRepository.CategoryRow r = mock(DashboardRepository.CategoryRow.class);
        when(r.getCheckType()).thenReturn(type);
        when(r.getTotal()).thenReturn(total);
        when(r.getPassing()).thenReturn(passing);
        return r;
    }

    // ---- trend ----
    @Test
    void trendRoundsToOneDecimalAndUsesNullWhenNothingWasAssessed() {
        DashboardRepository.TrendRow r1 = trend("2026-10-01", 3, 2); // 66.666... -> 66.7
        DashboardRepository.TrendRow r2 = trend("2026-10-02", 0, 0); // nothing assessed
        DashboardRepository.TrendRow r3 = trend("2026-10-03", 4, 4); // 100.0
        when(dashboard.trend(any(), any())).thenReturn(List.of(r1, r2, r3));

        List<TrendPoint> out = service.trend(3);

        assertEquals(66.7, out.get(0).compliantPercent());
        assertNull(out.get(1).compliantPercent());
        assertEquals(0, out.get(1).assessed());
        assertEquals(100.0, out.get(2).compliantPercent());
        assertEquals("2026-10-01", out.get(0).date());
    }

    @Test
    void trendWindowCoversTheRequestedNumberOfUtcDaysEndingToday() {
        when(dashboard.trend(any(), any())).thenReturn(List.of());
        service.trend(7);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(dashboard).trend(from.capture(), to.capture());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertEquals(today.atStartOfDay(ZoneOffset.UTC).toInstant(), to.getValue());
        assertEquals(today.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant(), from.getValue());
    }

    @Test
    void aOneDayTrendUsesTodayForBothEnds() {
        when(dashboard.trend(any(), any())).thenReturn(List.of());
        service.trend(1);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(dashboard).trend(from.capture(), to.capture());
        assertEquals(from.getValue(), to.getValue());
    }

    // ---- categories ----

    @Test
    void categoriesComputePassPercentAndNeverDivideByZero() {
        DashboardRepository.CategoryRow c1 = cat("FIREWALL", 3, 2);
        DashboardRepository.CategoryRow c2 = cat("OPEN_PORTS", 0, 0);
        DashboardRepository.CategoryRow c3 = cat("APPLICATIONS", 8, 8);
        when(dashboard.categories()).thenReturn(List.of(c1, c2, c3));

        List<CategoryRate> out = service.categories();

        assertEquals(66.7, out.get(0).passPercent());
        assertEquals(0.0, out.get(1).passPercent());
        assertEquals(100.0, out.get(2).passPercent());
        assertEquals("FIREWALL", out.get(0).checkType());
    }

    // ---- summary extras ----
    private Assessment a(UUID ep, AssessmentStatus s, Duration age) {
        return Assessment.builder().endpointId(ep).status(s).createdAt(Instant.now().minus(age)).build();
    }

    @Test
    void connectedDeviceWithNoAssessmentIsUnassessedAndNotCountedAsCompliant() {
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID();
        when(endpoints.count()).thenReturn(2L);
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(
                Endpoint.builder().id(c1).build(), Endpoint.builder().id(c2).build()));
        when(assessments.findLatestPerEndpoint())
                .thenReturn(List.of(a(c1, AssessmentStatus.COMPLIANT, Duration.ofHours(1))));

        Summary s = service.summary();

        assertEquals(1, s.compliant());
        assertEquals(1, s.unassessed());
        assertEquals(0, s.stale());
    }

    @Test
    void staleThresholdIsTwiceThePostureInterval() {
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID();
        when(endpoints.count()).thenReturn(2L);
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(
                Endpoint.builder().id(c1).build(), Endpoint.builder().id(c2).build()));
        when(assessments.findLatestPerEndpoint()).thenReturn(List.of(
                a(c1, AssessmentStatus.COMPLIANT, Duration.ofHours(7)), // inside 8h
                a(c2, AssessmentStatus.COMPLIANT, Duration.ofHours(9)))); // outside 8h

        assertEquals(1, service.summary().stale());
    }

    @Test
    void errorStatusIsCountedSeparatelyFromNonCompliant() {
        UUID c1 = UUID.randomUUID();
        when(endpoints.count()).thenReturn(1L);
        when(endpoints.findAllByConnectedTrue()).thenReturn(List.of(Endpoint.builder().id(c1).build()));
        when(assessments.findLatestPerEndpoint())
                .thenReturn(List.of(a(c1, AssessmentStatus.ERROR, Duration.ofMinutes(5))));

        Summary s = service.summary();
        assertEquals(1, s.error());
        assertEquals(0, s.nonCompliant());
    }
}