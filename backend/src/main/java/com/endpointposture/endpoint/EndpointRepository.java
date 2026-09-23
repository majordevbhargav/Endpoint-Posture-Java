package com.endpointposture.endpoint;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Endpoint}.
 */
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    /**
     * Looks an endpoint up by its business key.
     *
     * @param macAddress must already be normalized (see {@link EndpointService})
     * @return the endpoint, or empty if this MAC has never been seen
     */
    Optional<Endpoint> findByMacAddress(String macAddress);

    /**
     * @return every endpoint currently flagged as connected; used by the
     *         ISE session watcher to detect devices that dropped off
     */
    List<Endpoint> findAllByConnectedTrue();
}
