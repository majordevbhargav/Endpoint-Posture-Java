package com.endpointposture.endpoint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Endpoint}.
 */
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    /** Just the two columns the session watcher compares against ISE; no entity is loaded. */
    interface ConnectedRow {
        String getMacAddress();
        String getIpAddress();
    }

    /**
     * Looks an endpoint up by its business key.
     *
     * @param macAddress must already be normalized (see {@link EndpointService})
     * @return the endpoint, or empty if this MAC has never been seen
     */
    Optional<Endpoint> findByMacAddress(String macAddress);

    /**
     * @return every endpoint currently flagged as connected
     */
    List<Endpoint> findAllByConnectedTrue();

    /**
     * @return MAC and IP of every endpoint flagged as connected, as a light projection;
     *         used by the ISE session watcher's set diff
     */
    @Query("SELECT e.macAddress AS macAddress, e.ipAddress AS ipAddress FROM Endpoint e WHERE e.connected = true")
    List<ConnectedRow> findConnectedRows();
}