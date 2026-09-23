package com.endpointposture.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Data access for {@link IseActionAudit}. Reads only ever list rows - the trail is append-only. */
public interface IseActionAuditRepository extends JpaRepository<IseActionAudit, UUID> {

    /** @return one endpoint's ISE action history, newest first */
    List<IseActionAudit> findAllByEndpointIdOrderByOccurredAtDesc(UUID endpointId);

    /** @return the full ISE action history across every endpoint, newest first */
    List<IseActionAudit> findAllByOrderByOccurredAtDesc();
}