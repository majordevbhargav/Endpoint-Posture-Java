package com.endpointposture.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Writes today's compliance point into compliance_daily. Never throws into the scheduler. */
@Component
@ConditionalOnProperty(name = "app.dashboard.rollup.enabled", havingValue = "true", matchIfMissing = true)
public class TrendRollupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrendRollupScheduler.class);

    private static final String SQL = """
            INSERT INTO compliance_daily (day, assessed, compliant)
            SELECT (now() AT TIME ZONE 'UTC')::date,
                   COUNT(latest_status),
                   COUNT(*) FILTER (WHERE latest_status = 'COMPLIANT')
              FROM endpoint
            ON CONFLICT (day) DO UPDATE
               SET assessed = EXCLUDED.assessed, compliant = EXCLUDED.compliant, updated_at = now()
            """;

    private final JdbcTemplate jdbc;

    public TrendRollupScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${app.dashboard.rollup.interval-ms:900000}",
               initialDelayString = "${app.dashboard.rollup.initial-delay-ms:60000}")
    public void snapshot() {
        try {
            jdbc.update(SQL);
        } catch (Exception e) {
            log.error("Compliance rollup failed (will retry next interval)", e);
        }
    }
}