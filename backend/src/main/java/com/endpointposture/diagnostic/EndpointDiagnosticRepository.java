package com.endpointposture.diagnostic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Data access for {@link EndpointDiagnostic}. Append-only: no update or delete path. */
public interface EndpointDiagnosticRepository extends JpaRepository<EndpointDiagnostic, UUID> {

    /** @return that endpoint's runs, newest first */
    List<EndpointDiagnostic> findByEndpointIdOrderByCollectedAtDesc(UUID endpointId);

    /** @return its newest run, or empty if never diagnosed */
    Optional<EndpointDiagnostic> findFirstByEndpointIdOrderByCollectedAtDesc(UUID endpointId);
}