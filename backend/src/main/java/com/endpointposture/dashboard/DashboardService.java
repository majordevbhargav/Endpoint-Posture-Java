package com.endpointposture.dashboard;

import com.endpointposture.dashboard.DashboardDtos.CategoryRate;
import com.endpointposture.dashboard.DashboardDtos.Summary;
import com.endpointposture.dashboard.DashboardDtos.TrendPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only dashboard numbers. Never calls ISE. Everything here is one or two indexed
 * reads of the endpoint table (and compliance_daily), whatever the fleet size.
 *
 * <p>Trend: past UTC days come from the {@code compliance_daily} rollup, written every
 * 15 minutes by {@link TrendRollupScheduler} (a day's last snapshot is its final value).
 * Today is computed live. Days with no stored row show {@code null}.</p>
 */
@Service
public class DashboardService {

    private final DashboardRepository dashboard;
    private final long postureHours;

    public DashboardService(DashboardRepository dashboard,
                            @Value("${app.jobs.recheck.posture-hours:4}") long postureHours) {
        this.dashboard = dashboard;
        this.postureHours = postureHours;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Instant staleBefore = Instant.now().minus(Duration.ofHours(2 * postureHours));
        DashboardRepository.FleetRow r = dashboard.fleet(staleBefore);
        return new Summary(r.getTotal(), r.getConnected(), Math.max(0, r.getTotal() - r.getConnected()),
                r.getCompliant(), r.getNonCompliant(), r.getError(), r.getUnassessed(), r.getStale());
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trend(int days) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(days - 1L);

        Map<String, DashboardRepository.TrendRow> stored = new HashMap<>();
        if (from.isBefore(today)) {
            for (DashboardRepository.TrendRow r : dashboard.rollup(from, today.minusDays(1))) {
                stored.put(r.getDay(), r);
            }
        }
        DashboardRepository.AssessedRow now = dashboard.assessedNow();

        List<TrendPoint> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            if (d.equals(today)) {
                out.add(point(d.toString(), now.getAssessed(), now.getCompliant()));
            } else {
                DashboardRepository.TrendRow r = stored.get(d.toString());
                out.add(r == null ? point(d.toString(), 0, 0) : point(d.toString(), r.getAssessed(), r.getCompliant()));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<CategoryRate> categories() {
        return dashboard.categories().stream()
                .map(r -> new CategoryRate(r.getCheckType(), r.getTotal(), r.getPassing(),
                        r.getTotal() == 0 ? 0 : round1(100.0 * r.getPassing() / r.getTotal())))
                .toList();
    }

    private static TrendPoint point(String date, long assessed, long compliant) {
        return new TrendPoint(date, assessed, assessed == 0 ? null : round1(100.0 * compliant / assessed));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}