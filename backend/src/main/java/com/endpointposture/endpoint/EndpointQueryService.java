package com.endpointposture.endpoint;

import com.endpointposture.endpoint.dto.EndpointListItem;
import com.endpointposture.endpoint.dto.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.endpointposture.endpoint.dto.EndpointBrief;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Server-side search and paging over endpoints. Filters: free text (hostname, MAC, IP, OS),
 * connected flag, and posture status (comma list of COMPLIANT, NON_COMPLIANT, ERROR,
 * UNASSESSED; unknown values are ignored). Every value is a bound parameter.
 */
@Service
public class EndpointQueryService {

    public static final int MAX_SIZE = 200;
    private static final Set<String> STATUSES = Set.of("COMPLIANT", "NON_COMPLIANT", "ERROR", "UNASSESSED");
    private static final int MAX_NAMES = 100;

    private final JdbcTemplate jdbc;

    public EndpointQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<EndpointListItem> page(int page, int size, String q, Boolean connected, String status) {
        int safeSize = Math.max(1, Math.min(size, MAX_SIZE));
        int safePage = Math.max(0, page);

        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (connected != null) {
            where.add("connected = ?");
            args.add(connected);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.add("(hostname ILIKE ? ESCAPE '\\' OR mac_address ILIKE ? ESCAPE '\\' "
                    + "OR ip_address ILIKE ? ESCAPE '\\' OR os_name ILIKE ? ESCAPE '\\')");
            for (int i = 0; i < 4; i++) args.add(like);
        }
        Set<String> wanted = parseStatuses(status);
        if (!wanted.isEmpty()) {
            boolean unassessed = wanted.remove("UNASSESSED");
            List<String> parts = new ArrayList<>();
            if (!wanted.isEmpty()) {
                parts.add("latest_status IN (" + String.join(",", Collections.nCopies(wanted.size(), "?")) + ")");
                args.addAll(wanted);
            }
            if (unassessed) parts.add("latest_status IS NULL");
            where.add("(" + String.join(" OR ", parts) + ")");
        }
        String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

        Long total = jdbc.queryForObject("SELECT count(*) FROM endpoint" + whereSql, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<EndpointListItem> items = jdbc.query("""
                SELECT id, mac_address, ip_address, hostname, os_name, os_version, connected,
                       last_seen_at, latest_status, latest_assessed_at
                  FROM endpoint""" + whereSql
                + " ORDER BY connected DESC, last_seen_at DESC, id LIMIT ? OFFSET ?",
                (rs, i) -> new EndpointListItem(
                        rs.getObject("id", UUID.class), rs.getString("mac_address"), rs.getString("ip_address"),
                        rs.getString("hostname"), rs.getString("os_name"), rs.getString("os_version"),
                        rs.getBoolean("connected"), rs.getTimestamp("last_seen_at").toInstant(),
                        rs.getString("latest_status"), instant(rs.getTimestamp("latest_assessed_at"))),
                pageArgs.toArray());

        return new PageResponse<>(items, total == null ? 0 : total, safePage, safeSize);
    }

    /** Display name (hostname, else MAC) for up to 100 endpoint ids. */
    @Transactional(readOnly = true)
    public Map<String, String> names(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<UUID> limited = ids.size() > MAX_NAMES ? ids.subList(0, MAX_NAMES) : ids;
        Map<String, String> out = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement(
                    "SELECT id, COALESCE(NULLIF(hostname, ''), mac_address) AS name FROM endpoint WHERE id = ANY(?)");
            ps.setArray(1, con.createArrayOf("uuid", limited.toArray()));
            return ps;
        }, rs -> {
            out.put(rs.getString("id"), rs.getString("name"));
        });
        return out;
    }
    
    /** Hostname, MAC and IP for up to 100 endpoint ids. */
    @Transactional(readOnly = true)
    public Map<String, EndpointBrief> briefs(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<UUID> limited = ids.size() > MAX_NAMES ? ids.subList(0, MAX_NAMES) : ids;
        Map<String, EndpointBrief> out = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement(
                    "SELECT id, hostname, mac_address, ip_address FROM endpoint WHERE id = ANY(?)");
            ps.setArray(1, con.createArrayOf("uuid", limited.toArray()));
            return ps;
        }, rs -> {
            out.put(rs.getString("id"),
                    new EndpointBrief(rs.getString("hostname"), rs.getString("mac_address"), rs.getString("ip_address")));
        });
        return out;
    }

    private static Set<String> parseStatuses(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String s : raw.split(",")) {
            String v = s.trim().toUpperCase(Locale.ROOT);
            if (STATUSES.contains(v)) out.add(v);
        }
        return out;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}