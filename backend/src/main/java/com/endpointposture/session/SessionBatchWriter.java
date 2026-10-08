package com.endpointposture.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bulk writes for {@link IseSessionWatcher}. Each method handles a whole set of
 * devices in a few statements (arrays passed to Postgres {@code unnest}/{@code ANY}),
 * instead of several queries per device. Large sets are split into chunks of
 * {@link #CHUNK} so no statement carries an unbounded array.
 *
 * <p>Nothing here calls Cisco ISE. MAC addresses must already be normalized
 * (uppercase, colon separated); the watcher does that.</p>
 *
 * <p>{@link #enqueueReconnectChecks} applies the same rules as
 * {@code JobService.enqueueIfDue(id, type)}: skip if a job of that type is already
 * QUEUED or RUNNING, skip if one COMPLETED within
 * {@code app.jobs.reconnect-min-gap-minutes} (0 disables the gap), and skip devices
 * that have neither an IP address nor a hostname.</p>
 */
@Component
public class SessionBatchWriter {

    static final int CHUNK = 5000;

    /** Do not rewrite last_seen_at more often than this; it is not worth a write per poll. */
    private static final String TOUCH_SQL = """
            UPDATE endpoint
               SET last_seen_at = now(), updated_at = now()
             WHERE mac_address = ANY(?::text[])
               AND last_seen_at < now() - (?::bigint * interval '1 minute')
            """;

    private static final String CONNECT_SQL = """
            INSERT INTO endpoint (mac_address, ip_address, connected, session_started_at,
                                  first_seen_at, last_seen_at, created_at, updated_at)
            SELECT t.m, t.i, true, now(), now(), now(), now(), now()
              FROM unnest(?::text[], ?::text[]) AS t(m, i)
            ON CONFLICT (mac_address) DO UPDATE
               SET connected          = true,
                   ip_address         = COALESCE(EXCLUDED.ip_address, endpoint.ip_address),
                   session_started_at = now(),
                   last_seen_at       = now(),
                   updated_at         = now()
            """;

    private static final String LOG_CONNECTED_SQL = """
            INSERT INTO endpoint_session_log (endpoint_id, event_type, ip_address)
            SELECT id, 'CONNECTED', ip_address
              FROM endpoint
             WHERE mac_address = ANY(?::text[])
            """;

    private static final String IP_SQL = """
            UPDATE endpoint e
               SET ip_address = t.i, updated_at = now()
              FROM unnest(?::text[], ?::text[]) AS t(m, i)
             WHERE e.mac_address = t.m
            """;

    private static final String DISCONNECT_SQL = """
            WITH changed AS (
                UPDATE endpoint
                   SET connected = false, last_disconnected_at = now(), updated_at = now()
                 WHERE mac_address = ANY(?::text[]) AND connected
             RETURNING id
            )
            INSERT INTO endpoint_session_log (endpoint_id, event_type)
            SELECT id, 'DISCONNECTED' FROM changed
            """;

    private static final String ENQUEUE_SQL = """
            INSERT INTO posture_job (endpoint_id, job_type, status, priority, attempt_count, max_attempts)
            SELECT e.id, 'POSTURE_CHECK', 'QUEUED', 0, 0, 3
              FROM endpoint e
             WHERE e.mac_address = ANY(?::text[])
               AND (COALESCE(e.ip_address, '') <> '' OR COALESCE(e.hostname, '') <> '')
               AND NOT EXISTS (SELECT 1 FROM posture_job j
                                WHERE j.endpoint_id = e.id AND j.job_type = 'POSTURE_CHECK'
                                  AND j.status IN ('QUEUED', 'RUNNING'))
               AND (?::bigint <= 0 OR NOT EXISTS (SELECT 1 FROM posture_job j
                                WHERE j.endpoint_id = e.id AND j.job_type = 'POSTURE_CHECK'
                                  AND j.status = 'COMPLETE'
                                  AND j.completed_at > now() - (?::bigint * interval '1 minute')))
            """;

    private final JdbcTemplate jdbc;
    private final long reconnectGapMinutes;
    private final long touchMinMinutes;

    public SessionBatchWriter(JdbcTemplate jdbc,
                              @Value("${app.jobs.reconnect-min-gap-minutes:60}") long reconnectGapMinutes,
                              @Value("${app.ise.touch-min-minutes:5}") long touchMinMinutes) {
        this.jdbc = jdbc;
        this.reconnectGapMinutes = reconnectGapMinutes;
        this.touchMinMinutes = touchMinMinutes;
    }

    /**
     * Creates or flips to connected every device in the map (MAC to IP, IP may be null),
     * and writes one CONNECTED session-log row each. Call only with devices that were
     * NOT connected before.
     *
     * @return how many devices were processed
     */
    @Transactional
    public int markConnected(Map<String, String> macToIp) {
        if (macToIp.isEmpty()) return 0;
        List<String> macs = new ArrayList<>(macToIp.keySet());

        for (List<String> chunk : chunks(macs)) {
            List<String> ips = new ArrayList<>(chunk.size());
            for (String mac : chunk) ips.add(macToIp.get(mac));
            jdbc.update(CONNECT_SQL, ps -> {
                setTextArray(ps, 1, chunk);
                setTextArray(ps, 2, ips);
            });
            jdbc.update(LOG_CONNECTED_SQL, ps -> setTextArray(ps, 1, chunk));
        }
        return macs.size();
    }

    /**
     * Queues one automatic POSTURE_CHECK per device that is due (see class comment).
     *
     * @return how many jobs were inserted
     */
    @Transactional
    public int enqueueReconnectChecks(List<String> macs) {
        int inserted = 0;
        for (List<String> chunk : chunks(macs)) {
            inserted += jdbc.update(ENQUEUE_SQL, ps -> {
                setTextArray(ps, 1, chunk);
                ps.setLong(2, reconnectGapMinutes);
                ps.setLong(3, reconnectGapMinutes);
            });
        }
        return inserted;
    }

    /** Stores a changed IP for each device in the map (MAC to new IP). */
    @Transactional
    public void updateIps(Map<String, String> macToNewIp) {
        if (macToNewIp.isEmpty()) return;
        List<String> macs = new ArrayList<>(macToNewIp.keySet());
        for (List<String> chunk : chunks(macs)) {
            List<String> ips = new ArrayList<>(chunk.size());
            for (String mac : chunk) ips.add(macToNewIp.get(mac));
            jdbc.update(IP_SQL, ps -> {
                setTextArray(ps, 1, chunk);
                setTextArray(ps, 2, ips);
            });
        }
    }

    /** Refreshes {@code last_seen_at} for devices still connected (only if older than touchMinMinutes). */
    @Transactional
    public void touchSeen(List<String> macs) {
        for (List<String> chunk : chunks(macs)) {
            jdbc.update(TOUCH_SQL, ps -> {
                setTextArray(ps, 1, chunk);
                ps.setLong(2, touchMinMinutes);
            });
        }
    }

    /**
     * Marks devices disconnected and writes one DISCONNECTED log row for each device that
     * was actually connected. Posture history is untouched.
     *
     * @return how many devices changed state
     */
    @Transactional
    public int markDisconnected(List<String> macs) {
        int changed = 0;
        for (List<String> chunk : chunks(macs)) {
            changed += jdbc.update(DISCONNECT_SQL, ps -> setTextArray(ps, 1, chunk));
        }
        return changed;
    }

    private static List<List<String>> chunks(List<String> all) {
        List<List<String>> out = new ArrayList<>();
        for (int from = 0; from < all.size(); from += CHUNK) {
            out.add(all.subList(from, Math.min(all.size(), from + CHUNK)));
        }
        return out;
    }

    private static void setTextArray(PreparedStatement ps, int index, List<String> values) throws SQLException {
        ps.setArray(index, ps.getConnection().createArrayOf("text", values.toArray()));
    }
}