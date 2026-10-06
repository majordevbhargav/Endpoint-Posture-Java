package com.endpointposture.hardware;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HardwareHealthRepository extends JpaRepository<HardwareHealthReport, UUID> {

        List<HardwareHealthReport> findByEndpointIdOrderByCollectedAtDesc(UUID endpointId);

        Optional<HardwareHealthReport> findFirstByEndpointIdOrderByCollectedAtDesc(UUID endpointId);

        /** @return the newest run for an endpoint that actually produced scores */
        Optional<HardwareHealthReport> findFirstByEndpointIdAndSucceededTrueOrderByCollectedAtDesc(UUID endpointId);

        /**
         * @return the newest hardware report (failed or not) for every endpoint that
         *         has one
         */
        @Query(value = """
                        SELECT DISTINCT ON (endpoint_id) *
                        FROM hardware_health
                        ORDER BY endpoint_id, collected_at DESC
                        """, nativeQuery = true)
        List<HardwareHealthReport> findLatestPerEndpoint();

        /**
         * @return the newest SUCCESSFUL hardware report for every endpoint that has one
         */
        @Query(value = """
                        SELECT DISTINCT ON (endpoint_id) *
                        FROM hardware_health
                        WHERE succeeded = TRUE
                        ORDER BY endpoint_id, collected_at DESC
                        """, nativeQuery = true)
        List<HardwareHealthReport> findLatestSuccessfulPerEndpoint();

        /** Moves the "newest attempt" pointer forward, never backwards. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE endpoint
               SET latest_hw_id = :id, latest_hw_at = CAST(:at AS timestamptz), latest_hw_succeeded = :ok
             WHERE id = :endpointId
               AND (latest_hw_at IS NULL OR latest_hw_at <= CAST(:at AS timestamptz))
            """, nativeQuery = true)
    int updateEndpointLatest(@Param("endpointId") UUID endpointId, @Param("id") UUID id,
                             @Param("ok") boolean ok, @Param("at") Instant at);

    /** Moves the "last successful run" pointer forward, never backwards. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE endpoint
               SET good_hw_id = :id, good_hw_at = CAST(:at AS timestamptz), good_hw_band = :band,
                   good_hw_score = :score, good_hw_battery = CAST(:battery AS integer)
             WHERE id = :endpointId
               AND (good_hw_at IS NULL OR good_hw_at <= CAST(:at AS timestamptz))
            """, nativeQuery = true)
    int updateEndpointGood(@Param("endpointId") UUID endpointId, @Param("id") UUID id,
                           @Param("band") String band, @Param("score") int score,
                           @Param("battery") Integer battery, @Param("at") Instant at);
}