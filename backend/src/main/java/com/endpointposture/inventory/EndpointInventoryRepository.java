package com.endpointposture.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EndpointInventoryRepository extends JpaRepository<EndpointInventory, UUID> {

    /** @return the newest inventory row for every endpoint that has one */
    @Query(value = """
            SELECT DISTINCT ON (endpoint_id) *
            FROM endpoint_inventory
            ORDER BY endpoint_id, collected_at DESC
            """, nativeQuery = true)
    List<EndpointInventory> findLatestPerEndpoint();

    /** Moves the endpoint's "latest inventory" pointer forward; never backwards (V23). */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE endpoint
               SET latest_inventory_id = :id, latest_inventory_at = CAST(:at AS timestamptz)
             WHERE id = :endpointId
               AND (latest_inventory_at IS NULL OR latest_inventory_at <= CAST(:at AS timestamptz))
            """, nativeQuery = true)
    int updateEndpointLatest(@Param("endpointId") UUID endpointId, @Param("id") UUID id, @Param("at") Instant at);
}