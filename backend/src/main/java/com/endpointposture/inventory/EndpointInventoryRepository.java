package com.endpointposture.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}