package com.endpointposture.dashboard;

import com.endpointposture.dashboard.DashboardDtos.CategoryRate;
import com.endpointposture.dashboard.DashboardDtos.Summary;
import com.endpointposture.dashboard.DashboardDtos.TrendPoint;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.posture.Assessment;
import com.endpointposture.posture.AssessmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Read-only dashboard numbers. Never calls ISE.
 *
 * <p>Trend definition: for each UTC day, take every endpoint's latest assessment
 * created before the end of that day; the percentage is COMPLIANT / assessed.</p>
 */
@Service
public class DashboardService {

    private final EndpointRepository endpoints;
    private final AssessmentRepository assessments;
    private final DashboardRepository dashboard;
    private final long postureHours;

    public DashboardService(EndpointRepository endpoints,
                            AssessmentRepository assessments,
                            DashboardRepository dashboard,
                            @Value("${app.jobs.recheck.posture-hours:4}") long postureHours) {
        this.endpoints = endpoints;
        this.assessments = assessments;
        this.dashboard = dashboard;
        this.postureHours = postureHours;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        long total = endpoints.count();
        long connected = endpoints.findAllByConnectedTrue().size();
        List<Assessment> latest = assessments.findLatestPerEndpoint();

        Instant staleBefore = Instant.now().minus(Duration.ofHours(2 * postureHours));
        long compliant = 0, nonCompliant = 0, error = 0, stale = 0;

        for (Assessment a : latest) {
            switch (a.getStatus()) {
                case COMPLIANT -> compliant++;
                case NON_COMPLIANT -> nonCompliant++;
                case ERROR -> error++;
            }
            if (a.getCreatedAt() != null && a.getCreatedAt().isBefore(staleBefore)) stale++;
        }

        long unassessed = Math.max(0, total - latest.size());
        return new Summary(total, connected, Math.max(0, total - connected),
                compliant, nonCompliant, error, unassessed, stale);
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trend(int days) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant from = today.minusDays(days - 1L).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = today.atStartOfDay(ZoneOffset.UTC).toInstant();

        return dashboard.trend(from, to).stream()
                .map(r -> new TrendPoint(
                        r.getDay(),
                        r.getAssessed(),
                        r.getAssessed() == 0 ? null : round1(100.0 * r.getCompliant() / r.getAssessed())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryRate> categories() {
        return dashboard.categories().stream()
                .map(r -> new CategoryRate(
                        r.getCheckType(),
                        r.getTotal(),
                        r.getPassing(),
                        r.getTotal() == 0 ? 0 : round1(100.0 * r.getPassing() / r.getTotal())))
                .toList();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}