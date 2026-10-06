package com.endpointposture.dashboard;

import com.endpointposture.posture.Assessment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-only aggregate queries for the dashboard. All of them read the endpoint table, not assessment history. */
public interface DashboardRepository extends Repository<Assessment, UUID> {

    interface FleetRow {
        long getTotal(); long getConnected(); long getCompliant(); long getNonCompliant();
        long getError(); long getUnassessed(); long getStale();
    }

    interface AssessedRow { long getAssessed(); long getCompliant(); }

    interface TrendRow { String getDay(); long getAssessed(); long getCompliant(); }

    interface CategoryRow { String getCheckType(); long getTotal(); long getPassing(); }

    /** One pass over endpoint: totals, and posture buckets for connected devices only. */
    @Query(value = """
            SELECT COUNT(*) AS "total",
                   COUNT(*) FILTER (WHERE connected) AS "connected",
                   COUNT(*) FILTER (WHERE connected AND latest_status = 'COMPLIANT') AS "compliant",
                   COUNT(*) FILTER (WHERE connected AND latest_status = 'NON_COMPLIANT') AS "nonCompliant",
                   COUNT(*) FILTER (WHERE connected AND latest_status = 'ERROR') AS "error",
                   COUNT(*) FILTER (WHERE connected AND latest_status IS NULL) AS "unassessed",
                   COUNT(*) FILTER (WHERE connected AND latest_status IS NOT NULL
                                      AND latest_assessed_at < CAST(:staleBefore AS timestamptz)) AS "stale"
              FROM endpoint
            """, nativeQuery = true)
    FleetRow fleet(@Param("staleBefore") Instant staleBefore);

    /** Today's live point: every endpoint with an assessment, and how many of them are COMPLIANT. */
    @Query(value = """
            SELECT COUNT(latest_status) AS "assessed",
                   COUNT(*) FILTER (WHERE latest_status = 'COMPLIANT') AS "compliant"
              FROM endpoint
            """, nativeQuery = true)
    AssessedRow assessedNow();

    /** Stored daily points, inclusive. */
    @Query(value = """
            SELECT to_char(day, 'YYYY-MM-DD') AS "day",
                   CAST(assessed AS bigint) AS "assessed",
                   CAST(compliant AS bigint) AS "compliant"
              FROM compliance_daily
             WHERE day BETWEEN CAST(:from AS date) AND CAST(:to AS date)
             ORDER BY day
            """, nativeQuery = true)
    List<TrendRow> rollup(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Pass counts per check type, from each endpoint's latest assessment. */
    @Query(value = """
            SELECT cr.check_type AS "checkType",
                   COUNT(*) AS "total",
                   COUNT(*) FILTER (WHERE cr.status = 'COMPLIANT') AS "passing"
              FROM endpoint e
              JOIN check_result cr ON cr.assessment_id = e.latest_assessment_id
              WHERE e.connected
             GROUP BY cr.check_type
             ORDER BY cr.check_type
            """, nativeQuery = true)
    List<CategoryRow> categories();
}