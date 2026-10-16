package com.endpointposture.isefeed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The standing ISE sharing rule (DECISIONS D3, option B).
 *
 * <p>The rule is the human decision: an ADMIN approves a scope, the change is a new
 * version attributed to that admin, and old versions are kept forever. Collecting
 * a result never shares it by itself; only what the active rule covers can appear
 * in the feed. Restrict and Clear are never covered by a rule.</p>
 */
@Service
public class SharingRuleService {

    /** What the feed may contain. {@code NONE} is the seeded default: an empty feed. */
    public enum Scope {
        /** Nothing is shared. */
        NONE,
        /** Every connected endpoint that has been assessed. */
        CONNECTED,
        /** Connected endpoints whose latest assessment is NON_COMPLIANT or ERROR. */
        CONNECTED_NON_COMPLIANT
    }

    public record Rule(UUID id, int version, Scope scope, boolean active, String createdBy, Instant createdAt) {}

    private static final RowMapper<Rule> MAPPER = (rs, i) -> new Rule(
            rs.getObject("id", UUID.class), rs.getInt("version"), Scope.valueOf(rs.getString("scope")),
            rs.getBoolean("active"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS = "id, version, scope, active, created_by, created_at";

    private final JdbcTemplate jdbc;

    public SharingRuleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws IllegalStateException if no rule is active (the migration seeds one; fail loudly rather than guess) */
    @Transactional(readOnly = true)
    public Rule getActive() {
        List<Rule> active = jdbc.query("SELECT " + COLUMNS + " FROM ise_sharing_rule WHERE active", MAPPER);
        if (active.isEmpty()) {
            throw new IllegalStateException("No active ISE sharing rule exists");
        }
        return active.get(0);
    }

    @Transactional(readOnly = true)
    public List<Rule> history() {
        return jdbc.query("SELECT " + COLUMNS + " FROM ise_sharing_rule ORDER BY version DESC", MAPPER);
    }

    /**
     * Makes {@code scope} the active rule. Selecting the scope that is already active
     * changes nothing and returns the current rule (no empty version).
     *
     * @param operator the authenticated ADMIN, stored as {@code created_by}
     */
    @Transactional
    public Rule replaceActive(Scope scope, String operator) {
        // Serialise concurrent edits so two admins cannot both claim version N+1.
        jdbc.execute("LOCK TABLE ise_sharing_rule IN SHARE ROW EXCLUSIVE MODE");

        Rule current = getActive();
        if (current.scope() == scope) {
            return current;
        }
        Integer next = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM ise_sharing_rule", Integer.class);
        // Deactivate first, then insert: the partial unique index allows one active row.
        jdbc.update("UPDATE ise_sharing_rule SET active = false WHERE active");
        return jdbc.queryForObject(
                "INSERT INTO ise_sharing_rule (version, scope, active, created_by) VALUES (?, ?, true, ?) RETURNING "
                        + COLUMNS,
                MAPPER, next, scope.name(), operator);
    }
}