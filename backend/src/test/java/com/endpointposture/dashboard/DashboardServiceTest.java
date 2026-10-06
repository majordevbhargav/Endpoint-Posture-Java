package com.endpointposture.dashboard;

import com.endpointposture.dashboard.DashboardDtos.CategoryRate;
import com.endpointposture.dashboard.DashboardDtos.Summary;
import com.endpointposture.dashboard.DashboardDtos.TrendPoint;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    @Mock
    DashboardRepository dashboard;

    DashboardService service;

    final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new DashboardService(dashboard, 4);

        DashboardRepository.AssessedRow none = assessed(0, 0);
        when(dashboard.assessedNow()).thenReturn(none);
    }

    private DashboardRepository.AssessedRow assessed(long assessed, long compliant) {
        DashboardRepository.AssessedRow row =
                mock(DashboardRepository.AssessedRow.class);

        when(row.getAssessed()).thenReturn(assessed);
        when(row.getCompliant()).thenReturn(compliant);

        return row;
    }

    private DashboardRepository.TrendRow day(
            LocalDate date,
            long assessed,
            long compliant) {

        DashboardRepository.TrendRow row =
                mock(DashboardRepository.TrendRow.class);

        when(row.getDay()).thenReturn(date.toString());
        when(row.getAssessed()).thenReturn(assessed);
        when(row.getCompliant()).thenReturn(compliant);

        return row;
    }

    private DashboardRepository.FleetRow fleet(
            long total,
            long connected,
            long compliant,
            long nonCompliant,
            long error,
            long unassessed,
            long stale) {

        DashboardRepository.FleetRow row =
                mock(DashboardRepository.FleetRow.class);

        when(row.getTotal()).thenReturn(total);
        when(row.getConnected()).thenReturn(connected);
        when(row.getCompliant()).thenReturn(compliant);
        when(row.getNonCompliant()).thenReturn(nonCompliant);
        when(row.getError()).thenReturn(error);
        when(row.getUnassessed()).thenReturn(unassessed);
        when(row.getStale()).thenReturn(stale);

        return row;
    }

    @Test
    void summaryMapsTheFleetRowAndDerivesNotConnected() {

        // Create the Mockito mock before starting the outer stubbing.
        DashboardRepository.FleetRow row =
                fleet(5, 2, 1, 1, 0, 0, 1);

        when(dashboard.fleet(any())).thenReturn(row);

        Summary summary = service.summary();

        assertEquals(5, summary.total());
        assertEquals(3, summary.notConnected());
        assertEquals(1, summary.compliant());
        assertEquals(1, summary.nonCompliant());
        assertEquals(1, summary.stale());
    }

    @Test
    void staleCutoffIsTwiceThePostureInterval() {

        // Create the Mockito mock before starting the outer stubbing.
        DashboardRepository.FleetRow row =
                fleet(0, 0, 0, 0, 0, 0, 0);

        when(dashboard.fleet(any())).thenReturn(row);

        service.summary();

        ArgumentCaptor<Instant> captor =
                ArgumentCaptor.forClass(Instant.class);

        verify(dashboard).fleet(captor.capture());

        long hours =
                Duration.between(
                        captor.getValue(),
                        Instant.now()
                ).toHours();

        assertTrue(
                hours >= 7 && hours <= 8,
                "expected about 8 hours, got " + hours
        );
    }

    @Test
    void trendUsesStoredDaysFillsGapsWithNullAndComputesTodayLive() {

        // Create helper mocks before starting the outer stubbing.
        DashboardRepository.TrendRow storedDay =
                day(today.minusDays(2), 3, 2);

        DashboardRepository.AssessedRow live =
                assessed(4, 4);

        when(dashboard.rollup(any(), any()))
                .thenReturn(List.of(storedDay));

        when(dashboard.assessedNow())
                .thenReturn(live);

        List<TrendPoint> output =
                service.trend(3);

        assertEquals(3, output.size());

        assertEquals(
                today.minusDays(2).toString(),
                output.get(0).date()
        );

        assertEquals(
                66.7,
                output.get(0).compliantPercent()
        );

        assertNull(output.get(1).compliantPercent());

        assertEquals(
                0,
                output.get(1).assessed()
        );

        assertEquals(
                today.toString(),
                output.get(2).date()
        );

        assertEquals(
                100.0,
                output.get(2).compliantPercent()
        );
    }

    @Test
    void trendAsksTheRollupOnlyForPastDays() {

        service.trend(7);

        ArgumentCaptor<LocalDate> from =
                ArgumentCaptor.forClass(LocalDate.class);

        ArgumentCaptor<LocalDate> to =
                ArgumentCaptor.forClass(LocalDate.class);

        verify(dashboard).rollup(
                from.capture(),
                to.capture()
        );

        assertEquals(
                today.minusDays(6),
                from.getValue()
        );

        assertEquals(
                today.minusDays(1),
                to.getValue()
        );
    }

    @Test
    void aOneDayTrendIsLiveOnlyAndNeverTouchesTheRollup() {

        List<TrendPoint> output =
                service.trend(1);

        assertEquals(1, output.size());

        verify(
                dashboard,
                never()
        ).rollup(any(), any());
    }

    @Test
    void categoriesComputePassPercentAndNeverDivideByZero() {

        DashboardRepository.CategoryRow category1 =
                mock(DashboardRepository.CategoryRow.class);

        when(category1.getCheckType())
                .thenReturn("FIREWALL");

        when(category1.getTotal())
                .thenReturn(3L);

        when(category1.getPassing())
                .thenReturn(2L);

        DashboardRepository.CategoryRow category2 =
                mock(DashboardRepository.CategoryRow.class);

        when(category2.getCheckType())
                .thenReturn("OPEN_PORTS");

        when(category2.getTotal())
                .thenReturn(0L);

        when(category2.getPassing())
                .thenReturn(0L);

        // The category mocks are created before this outer stubbing.
        when(dashboard.categories())
                .thenReturn(List.of(category1, category2));

        List<CategoryRate> output =
                service.categories();

        assertEquals(
                66.7,
                output.get(0).passPercent()
        );

        assertEquals(
                0.0,
                output.get(1).passPercent()
        );
    }
}
