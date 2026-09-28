package com.endpointposture.hardware;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}