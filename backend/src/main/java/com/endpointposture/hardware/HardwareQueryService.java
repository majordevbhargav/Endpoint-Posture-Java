package com.endpointposture.hardware;

import com.endpointposture.endpoint.dto.PageResponse;
import com.endpointposture.hardware.dto.HardwareListItem;
import com.endpointposture.hardware.dto.HardwareSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Server-side search and paging over hardware health, from the endpoint pointers
 * (good_hw_*, latest_hw_*). band filter: HEALTHY, WARNING, DEGRADED, CRITICAL, FAILED
 * (never collected successfully), LAST_FAILED (newest attempt failed) or NO_REPORT.
 * Worst score first. All values are bound parameters.
 */
@Service
public class HardwareQueryService {

    public static final int MAX_SIZE = 200;
    private static final Set<String> BANDS = Set.of("HEALTHY", "WARNING", "DEGRADED", "CRITICAL");

    private final JdbcTemplate jdbc;

    public HardwareQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<HardwareListItem> page(int page, int size, String q, String band) {
        int safeSize = Math.max(1, Math.min(size, MAX_SIZE));
        int safePage = Math.max(0, page);

        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.add("(e.hostname ILIKE ? ESCAPE '\\' OR e.mac_address ILIKE ? ESCAPE '\\' OR g.model ILIKE ? ESCAPE '\\')");
            for (int i = 0; i < 3; i++) args.add(like);
        }
        String b = band == null ? "" : band.trim().toUpperCase(Locale.ROOT);
        switch (b) {
            case "FAILED" -> where.add("(e.latest_hw_id IS NOT NULL AND e.good_hw_id IS NULL)");
            case "LAST_FAILED" -> where.add("(e.good_hw_id IS NOT NULL AND e.latest_hw_succeeded = false)");
            case "NO_REPORT" -> where.add("e.latest_hw_id IS NULL");
            default -> {
                if (BANDS.contains(b)) {
                    where.add("e.good_hw_band = ?");
                    args.add(b);
                }
            }
        }
        String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM endpoint e LEFT JOIN hardware_health g ON g.id = e.good_hw_id" + whereSql,
                Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);

        List<HardwareListItem> items = jdbc.query("""
                SELECT e.id, e.hostname, e.mac_address,
                       g.id AS g_id, g.manufacturer, g.model, g.cpu_score, g.memory_score, g.storage_score,
                       g.battery_score, g.overall_score, g.overall_band, g.collected_at AS g_at,
                       l.id AS l_id, l.succeeded AS l_ok, l.collected_at AS l_at, l.error_message AS l_err,
                       (SELECT count(*) FROM hardware_recommendation r WHERE r.hardware_health_id = g.id) AS reco_count
                  FROM endpoint e
                  LEFT JOIN hardware_health g ON g.id = e.good_hw_id
                  LEFT JOIN hardware_health l ON l.id = e.latest_hw_id""" + whereSql
                + " ORDER BY e.good_hw_score ASC NULLS LAST, e.id LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), pageArgs.toArray());

        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public HardwareSummary summary() {
        HardwareSummary base = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(good_hw_id) AS with_report,
                       COALESCE(CAST(ROUND(AVG(good_hw_score)) AS bigint), 0) AS avg_score,
                       COUNT(*) FILTER (WHERE good_hw_band IN ('CRITICAL','DEGRADED')) AS crit,
                       COUNT(*) FILTER (WHERE good_hw_battery IS NOT NULL AND good_hw_battery < 70) AS battery,
                       COUNT(*) FILTER (WHERE good_hw_id IS NOT NULL AND latest_hw_succeeded = false) AS last_failed,
                       COUNT(*) FILTER (WHERE latest_hw_id IS NOT NULL AND good_hw_id IS NULL) AS never_ok,
                       COUNT(*) FILTER (WHERE latest_hw_id IS NULL) AS no_report
                  FROM endpoint
                """, (rs, i) -> new HardwareSummary(rs.getLong("total"), rs.getLong("with_report"),
                rs.getLong("avg_score"), rs.getLong("crit"), rs.getLong("battery"), rs.getLong("last_failed"),
                rs.getLong("never_ok"), rs.getLong("no_report"), 0));
        Long recs = jdbc.queryForObject(
                "SELECT count(*) FROM hardware_recommendation r JOIN endpoint e ON e.good_hw_id = r.hardware_health_id",
                Long.class);
        return new HardwareSummary(base.total(), base.withReport(), base.avgScore(), base.criticalOrDegraded(),
                base.batteryWarnings(), base.lastAttemptFailed(), base.neverCollected(), base.noReport(),
                recs == null ? 0 : recs);
    }

    private static HardwareListItem map(ResultSet rs) throws SQLException {
        UUID gId = rs.getObject("g_id", UUID.class);
        UUID lId = rs.getObject("l_id", UUID.class);
        UUID id = rs.getObject("id", UUID.class);
        String host = rs.getString("hostname");
        String mac = rs.getString("mac_address");

        if (lId == null) {
            return new HardwareListItem(id, host, mac, "NO_REPORT", null, null, null, null, null, null,
                    null, null, null, null, null, 0);
        }
        if (gId == null) { // never succeeded: only the failure is known
            return new HardwareListItem(id, host, mac, "FAILED", null, null, null, null, null, null,
                    null, null, instant(rs.getTimestamp("l_at")), null, rs.getString("l_err"), 0);
        }
        boolean newerFailed = !lId.equals(gId) && !rs.getBoolean("l_ok");
        return new HardwareListItem(id, host, mac, "OK", rs.getString("manufacturer"), rs.getString("model"),
                integer(rs, "cpu_score"), integer(rs, "memory_score"), integer(rs, "storage_score"),
                integer(rs, "battery_score"), integer(rs, "overall_score"), rs.getString("overall_band"),
                instant(rs.getTimestamp("g_at")),
                newerFailed ? instant(rs.getTimestamp("l_at")) : null,
                newerFailed ? rs.getString("l_err") : null,
                rs.getInt("reco_count"));
    }

    private static Integer integer(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col) == null ? null : rs.getInt(col);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}