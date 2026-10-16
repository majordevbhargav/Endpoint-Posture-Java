package com.endpointposture.isefeed;

import com.endpointposture.isefeed.SharingRuleService.Rule;
import com.endpointposture.isefeed.SharingRuleService.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Builds the flat JSON feed that ISE (pxGrid Direct) pulls on its own schedule.
 *
 * <p>The feed holds posture <em>facts</em> only, and only for endpoints the active
 * {@link SharingRuleService} rule covers. It contains no enforcement instruction: ISE's
 * own Authorization Policy decides what to do with the attributes. Every fetch writes
 * one {@code ise_feed_audit} row (caller, rule version, count; never the payload).
 * Read-only toward ISE: this class never calls it.</p>
 */
@Service
public class IseFeedService {

    public static final int MAX_SIZE = 50_000;

    /** One record of the feed. {@code mac} uses colons, matching ISE. */
    public record FeedEndpoint(String mac, String postureStatus, String failedChecks, Instant lastChecked,
                               boolean connected, String hardwareBand, String securityRiskLevel) {}

    public record Feed(int ruleVersion, String scope, Instant generatedAt, long total, int page, int size,
                       List<FeedEndpoint> endpoints) {}

    private static final String SELECT = """
            SELECT e.mac_address, e.latest_status, e.latest_assessed_at, e.connected, e.good_hw_band,
                   (SELECT string_agg(cr.check_type, ', ' ORDER BY cr.check_type)
                      FROM check_result cr
                     WHERE cr.assessment_id = e.latest_assessment_id AND cr.status <> 'COMPLIANT') AS failed,
                   (SELECT s.risk_level FROM endpoint_security_indicator s
                     WHERE s.endpoint_id = e.id AND s.status = 'OK'
                     ORDER BY s.collected_at DESC LIMIT 1) AS risk
              FROM endpoint e
             WHERE e.latest_status IS NOT NULL AND\s""";

    private final JdbcTemplate jdbc;
    private final SharingRuleService rules;

    public IseFeedService(JdbcTemplate jdbc, SharingRuleService rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    /**
     * @param page   zero-based
     * @param size   clamped to 1..{@link #MAX_SIZE}
     * @param caller who pulled the feed (the Basic-auth user or the admin), for the audit row
     */
    @Transactional
    public Feed fetch(int page, int size, String caller) {
        int safeSize = Math.max(1, Math.min(size, MAX_SIZE));
        int safePage = Math.max(0, page);
        Rule rule = rules.getActive();

        String scopeSql = scopeSql(rule.scope()); // fixed strings chosen by an enum, never user input
        long total = 0;
        List<FeedEndpoint> items = List.of();
        if (scopeSql != null) {
            Long count = jdbc.queryForObject("SELECT count(*) FROM endpoint e WHERE e.latest_status IS NOT NULL AND " + scopeSql, Long.class);
            total = count == null ? 0 : count;
            items = jdbc.query(SELECT + scopeSql + " ORDER BY e.mac_address LIMIT ? OFFSET ?",
                    (rs, i) -> new FeedEndpoint(
                            rs.getString("mac_address"),
                            rs.getString("latest_status"),
                            rs.getString("failed") == null ? "none" : rs.getString("failed"),
                            rs.getTimestamp("latest_assessed_at") == null ? null : rs.getTimestamp("latest_assessed_at").toInstant(),
                            rs.getBoolean("connected"),
                            rs.getString("good_hw_band"),
                            rs.getString("risk")),
                    safeSize, (long) safePage * safeSize);
        }

        jdbc.update("INSERT INTO ise_feed_audit (caller, rule_version, endpoint_count) VALUES (?, ?, ?)",
                caller, rule.version(), items.size());
        return new Feed(rule.version(), rule.scope().name(), Instant.now(), total, safePage, safeSize, items);
    }

    /** @return the extra WHERE condition for the scope, or {@code null} when nothing is shared */
    static String scopeSql(Scope scope) {
        return switch (scope) {
            case NONE -> null;
            case CONNECTED -> "e.connected";
            case CONNECTED_NON_COMPLIANT -> "e.connected AND e.latest_status IN ('NON_COMPLIANT','ERROR')";
        };
    }
}