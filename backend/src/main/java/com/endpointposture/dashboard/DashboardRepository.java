package com.endpointposture.dashboard;

import com.endpointposture.posture.Assessment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only aggregate queries for the dashboard. */
public interface DashboardRepository extends Repository<Assessment, UUID> {

    interface TrendRow {
        String getDay();
        long getAssessed();
        long getCompliant();
    }

    interface CategoryRow {
        String getCheckType();
        long getTotal();
        long getPassing();
    }

    /**
     * For each day between from and to (inclusive), each endpoint's latest
     * assessment created before the end of that day, counted overall and as COMPLIANT.
     */
    @Query(value = """
            SELECT to_char(d.day, 'YYYY-MM-DD') AS day,
                   COUNT(a.id) AS assessed,
                   COUNT(a.id) FILTER (WHERE a.status = 'COMPLIANT') AS compliant
            FROM generate_series(CAST(:from AS timestamptz), CAST(:to AS timestamptz), interval '1 day') AS d(day)
            CROSS JOIN endpoint e
            LEFT JOIN LATERAL (
                SELECT a2.id, a2.status
                FROM assessment a2
                WHERE a2.endpoint_id = e.id
                  AND a2.created_at < d.day + interval '1 day'
                ORDER BY a2.created_at DESC
                LIMIT 1
            ) a ON TRUE
            GROUP BY d.day
            ORDER BY d.day
            """, nativeQuery = true)
    List<TrendRow> trend(@Param("from") Instant from, @Param("to") Instant to);

    /** Pass counts per check type, using each endpoint's latest assessment only. */
    @Query(value = """
            SELECT cr.check_type AS "checkType",
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE cr.status = 'COMPLIANT') AS passing
            FROM check_result cr
            JOIN (
                SELECT DISTINCT ON (endpoint_id) id
                FROM assessment
                ORDER BY endpoint_id, created_at DESC
            ) la ON la.id = cr.assessment_id
            GROUP BY cr.check_type
            ORDER BY cr.check_type
            """, nativeQuery = true)
    List<CategoryRow> categories();
}