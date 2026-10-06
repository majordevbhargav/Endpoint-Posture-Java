package com.endpointposture.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Data access for {@link IseActionAudit}. Reads only ever list rows - the trail
 * is append-only.
 */
public interface IseActionAuditRepository extends JpaRepository<IseActionAudit, UUID> {

    /** @return one endpoint's ISE action history, newest first */
    List<IseActionAudit> findAllByEndpointIdOrderByOccurredAtDesc(UUID endpointId);

    /** @return the full ISE action history across every endpoint, newest first */
    List<IseActionAudit> findAllByOrderByOccurredAtDesc();

    List<IseActionAudit> findAllByEndpointIdOrderByOccurredAtDesc(UUID endpointId,
            org.springframework.data.domain.Pageable page);

    List<IseActionAudit> findAllByOrderByOccurredAtDesc(org.springframework.data.domain.Pageable page);

    /**
     * Finds the latest RESTRICT or CLEAR_RESTRICTION audit row per endpoint.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT DISTINCT ON (endpoint_id) *
            FROM ise_action_audit
            WHERE action_type IN ('RESTRICT', 'CLEAR_RESTRICTION')
            ORDER BY endpoint_id, occurred_at DESC
            """, nativeQuery = true)
    List<IseActionAudit> findLatestEnforcementActionsPerEndpoint();
}