package com.endpointposture.inventory;

import com.endpointposture.endpoint.dto.PageResponse;
import com.endpointposture.inventory.InventoryController.AppRow;
import com.endpointposture.inventory.InventoryController.PortRow;
import com.endpointposture.policy.PolicyService;
import com.endpointposture.policy.PolicyService.PolicySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side paging, search and filtering over installed applications and
 * listening ports (S13). Reads only each endpoint's newest inventory row (the
 * {@code endpoint.latest_inventory_id} pointer, V23) and expands its JSONB arrays
 * in SQL, so the browser never receives the whole fleet.
 *
 * <p>Application status is labelled in SQL against the active policy: blocked
 * wins over required, matching {@link InventoryController}. All user values are
 * bound parameters; LIKE wildcards in user text and policy patterns are escaped.
 * Malformed JSON (a non-array column, an element with no name or port) is skipped
 * rather than failing the query.</p>
 *
 * <p>Cost note: the {@code total} of an unfiltered list counts every expanded row.
 * At 50,000 endpoints with about 60 apps each that is a scan of about 3 million
 * elements, so expect seconds, not milliseconds; the UI shows the total only as
 * information.</p>
 */
@Service
public class InventoryQueryService {

    public static final int MAX_SIZE = 200;

    /** Separator between policy patterns in one bound parameter. PolicyService rejects control characters, so it cannot occur in a pattern. */
    private static final String SEP = "\u0001";

    private static final String APPS_INNER = """
            SELECT e.hostname, e.mac_address,
                   a.elem->>'name' AS name, a.elem->>'version' AS version, a.elem->>'publisher' AS publisher,
                   CASE WHEN EXISTS (SELECT 1 FROM unnest(string_to_array(?, chr(1))) AS b(p)
                                      WHERE (a.elem->>'name') ILIKE '%' || b.p || '%' ESCAPE '\\')
                        THEN 'NON_COMPLIANT'
                        WHEN EXISTS (SELECT 1 FROM unnest(string_to_array(?, chr(1))) AS r(p)
                                      WHERE (a.elem->>'name') ILIKE '%' || r.p || '%' ESCAPE '\\')
                        THEN 'COMPLIANT' END AS status
              FROM endpoint e
              JOIN endpoint_inventory i ON i.id = e.latest_inventory_id
              CROSS JOIN LATERAL jsonb_array_elements(
                    CASE WHEN jsonb_typeof(i.installed_apps) = 'array' THEN i.installed_apps ELSE '[]'::jsonb END) AS a(elem)
             WHERE jsonb_typeof(a.elem) = 'object' AND coalesce(btrim(a.elem->>'name'), '') <> ''
            """;

    private static final String PORTS_INNER = """
            SELECT e.hostname, e.mac_address,
                   floor((p.elem->>'port')::numeric)::int AS port,
                   p.elem->>'process' AS process,
                   CASE WHEN jsonb_typeof(p.elem->'pid') = 'number' THEN (p.elem->>'pid')::numeric::int END AS pid,
                   CASE WHEN jsonb_typeof(p.elem->'reachable') = 'boolean' THEN (p.elem->>'reachable')::boolean END AS reachable
              FROM endpoint e
              JOIN endpoint_inventory i ON i.id = e.latest_inventory_id
              CROSS JOIN LATERAL jsonb_array_elements(
                    CASE WHEN jsonb_typeof(i.listening_ports) = 'array' THEN i.listening_ports ELSE '[]'::jsonb END) AS p(elem)
             WHERE jsonb_typeof(p.elem) = 'object' AND jsonb_typeof(p.elem->'port') = 'number'
            """;

    /** Fleet numbers for the KPI cards of the Installed Software page. */
    public record AppSummary(long evaluatedEndpoints, long totalApps, long compliant, long nonCompliant) {}

    /** Fleet numbers for the KPI cards of the Listening Ports page. */
    public record PortSummary(long evaluatedEndpoints, long totalPorts, long reachable, long blocked) {}

    private final JdbcTemplate jdbc;
    private final PolicyService policyService;

    public InventoryQueryService(JdbcTemplate jdbc, PolicyService policyService) {
        this.jdbc = jdbc;
        this.policyService = policyService;
    }

    /**
     * @param status {@code COMPLIANT} (required app present) or {@code NON_COMPLIANT}
     *               (blocked app found); anything else means no status filter
     * @param q      free text over name, publisher, hostname and MAC
     */
    @Transactional(readOnly = true)
    public PageResponse<AppRow> applications(int page, int size, String q, String status) {
        int safeSize = clampSize(size);
        int safePage = Math.max(0, page);
        PolicySnapshot policy = policyService.getActive();

        List<Object> args = new ArrayList<>();
        args.add(patterns(policy.blockedApps()));
        args.add(patterns(policy.requiredApps()));

        StringBuilder sql = new StringBuilder("SELECT * FROM (" + APPS_INNER + ") x WHERE true");
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (st.equals("COMPLIANT") || st.equals("NON_COMPLIANT")) {
            sql.append(" AND x.status = ?");
            args.add(st);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (x.name ILIKE ? ESCAPE '\\' OR x.publisher ILIKE ? ESCAPE '\\'"
                    + " OR x.hostname ILIKE ? ESCAPE '\\' OR x.mac_address ILIKE ? ESCAPE '\\')");
            String like = like(q);
            for (int i = 0; i < 4; i++) args.add(like);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + sql + ") c", Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        int version = policy.version();
        List<AppRow> rows = jdbc.query(
                sql + " ORDER BY x.hostname NULLS LAST, x.mac_address, x.name LIMIT ? OFFSET ?",
                (rs, i) -> appRow(rs, version), pageArgs.toArray());

        return new PageResponse<>(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /**
     * @param reach {@code REACHABLE} or {@code BLOCKED}; anything else means no filter
     * @param q     free text over port, process, hostname and MAC
     */
    @Transactional(readOnly = true)
    public PageResponse<PortRow> ports(int page, int size, String q, String reach) {
        int safeSize = clampSize(size);
        int safePage = Math.max(0, page);

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM (" + PORTS_INNER + ") x WHERE true");
        String r = reach == null ? "" : reach.trim().toUpperCase(Locale.ROOT);
        if (r.equals("REACHABLE")) sql.append(" AND x.reachable = true");
        if (r.equals("BLOCKED")) sql.append(" AND x.reachable = false");
        if (q != null && !q.isBlank()) {
            sql.append(" AND (x.port::text ILIKE ? ESCAPE '\\' OR x.process ILIKE ? ESCAPE '\\'"
                    + " OR x.hostname ILIKE ? ESCAPE '\\' OR x.mac_address ILIKE ? ESCAPE '\\')");
            String like = like(q);
            for (int i = 0; i < 4; i++) args.add(like);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + sql + ") c", Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<PortRow> rows = jdbc.query(
                sql + " ORDER BY x.hostname NULLS LAST, x.mac_address, x.port LIMIT ? OFFSET ?",
                (rs, i) -> portRow(rs), pageArgs.toArray());

        return new PageResponse<>(rows, total == null ? 0 : total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public AppSummary applicationsSummary() {
        PolicySnapshot policy = policyService.getActive();
        Long evaluated = jdbc.queryForObject(
                "SELECT count(*) FROM endpoint WHERE latest_inventory_id IS NOT NULL", Long.class);
        Long total = jdbc.queryForObject("""
                SELECT coalesce(sum(CASE WHEN jsonb_typeof(i.installed_apps) = 'array'
                                         THEN jsonb_array_length(i.installed_apps) ELSE 0 END), 0)
                  FROM endpoint e JOIN endpoint_inventory i ON i.id = e.latest_inventory_id
                """, Long.class);

        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("SELECT x.status, count(*) AS n FROM (" + APPS_INNER + ") x WHERE x.status IS NOT NULL GROUP BY x.status",
                rs -> {
                    byStatus.put(rs.getString("status"), rs.getLong("n"));
                },
                patterns(policy.blockedApps()), patterns(policy.requiredApps()));

        return new AppSummary(nz(evaluated), nz(total),
                byStatus.getOrDefault("COMPLIANT", 0L), byStatus.getOrDefault("NON_COMPLIANT", 0L));
    }

    @Transactional(readOnly = true)
    public PortSummary portsSummary() {
        Long evaluated = jdbc.queryForObject(
                "SELECT count(*) FROM endpoint WHERE latest_inventory_id IS NOT NULL", Long.class);
        long[] counts = jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE x.reachable = true), count(*) FILTER (WHERE x.reachable = false)
                  FROM (""" + PORTS_INNER + ") x",
                (rs, i) -> new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) });
        return new PortSummary(nz(evaluated), counts[0], counts[1], counts[2]);
    }

    // ---- helpers ----

    private static AppRow appRow(ResultSet rs, int policyVersion) throws SQLException {
        String status = rs.getString("status");
        String summary = null;
        if ("NON_COMPLIANT".equals(status)) {
            summary = "Blocked application installed (policy v" + policyVersion + ")";
        } else if ("COMPLIANT".equals(status)) {
            summary = "Required application present (policy v" + policyVersion + ")";
        }
        return new AppRow(rs.getString("name"), rs.getString("version"), rs.getString("publisher"),
                rs.getString("hostname"), rs.getString("mac_address"), status, summary);
    }

    private static PortRow portRow(ResultSet rs) throws SQLException {
        Boolean reachable = (Boolean) rs.getObject("reachable");
        Integer pid = (Integer) rs.getObject("pid");
        String status = reachable == null ? null : (reachable ? "COMPLIANT" : "BLOCKED");
        return new PortRow(rs.getInt("port"), rs.getString("process"), pid, reachable,
                rs.getString("hostname"), rs.getString("mac_address"), status);
    }

    /** Policy patterns as one separator-joined string, with LIKE wildcards escaped; empty string means no patterns. */
    static String patterns(List<String> list) {
        List<String> escaped = new ArrayList<>();
        for (String p : list) escaped.add(escapeLike(p));
        return String.join(SEP, escaped);
    }

    static String like(String q) {
        return "%" + escapeLike(q.trim()) + "%";
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static int clampSize(int size) {
        return Math.max(1, Math.min(size, MAX_SIZE));
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}