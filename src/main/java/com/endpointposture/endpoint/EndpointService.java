package com.endpointposture.endpoint;

import com.endpointposture.endpoint.dto.EndpointResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Business logic for endpoints: reads for the API, plus the write paths
 * used by collectors (posture ingestion, the future ISE session watcher).
 *
 * <p>All MAC addresses pass through {@link #normalizeMac(String)} first, so
 * {@code aa-bb-cc-dd-ee-ff} (how Windows prints it) and
 * {@code AA:BB:CC:DD:EE:FF} (how ISE prints it) always resolve to the same
 * row.</p>
 */
@Service
public class EndpointService {

    private final EndpointRepository repository;

    public EndpointService(EndpointRepository repository) {
        this.repository = repository;
    }

    /**
     * Converts a MAC address to the one format stored in the database:
     * trimmed, uppercase, colon-separated.
     *
     * @param mac a MAC in any common separator style
     * @return e.g. {@code AA:BB:CC:DD:EE:FF}
     */
    static String normalizeMac(String mac) {
        return mac.trim().replace('-', ':').toUpperCase(Locale.ROOT);
    }

    /** @return every known endpoint, connected or not */
    @Transactional(readOnly = true)
    public List<EndpointResponse> listAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * @param id the endpoint's internal UUID
     * @return the endpoint
     * @throws EndpointNotFoundException if no endpoint has this ID
     */
    @Transactional(readOnly = true)
    public EndpointResponse getById(UUID id) {
        return repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new EndpointNotFoundException(id.toString()));
    }

    /**
     * Upsert-by-MAC - this is the real ingestion path. Every collector
     * (posture agent, ISE watcher) calls this, never a bare save(), so
     * the MAC uniqueness constraint is the single source of truth for
     * "is this a device we already know about."
     *
     * <p>Null arguments mean "no new information" and leave the stored
     * value untouched.</p>
     *
     * @return the saved endpoint (existing row updated, or a new one created)
     */
    @Transactional
    public Endpoint upsertByMac(String macAddress, String ip, String hostname, String os, String osVersion) {
        String normalizedMac = normalizeMac(macAddress);

        Endpoint endpoint = repository.findByMacAddress(normalizedMac)
                .orElseGet(() -> Endpoint.builder().macAddress(normalizedMac).build());

        if (ip != null) endpoint.setIpAddress(ip);
        if (hostname != null) endpoint.setHostname(hostname);
        if (os != null) endpoint.setOsName(os);
        if (osVersion != null) endpoint.setOsVersion(osVersion);
        endpoint.setLastSeenAt(Instant.now());

        return repository.save(endpoint);
    }

    /**
     * Records the hardware identity reported by the posture agent.
     * Null arguments leave the stored value untouched. Does nothing if the
     * endpoint no longer exists.
     */
    @Transactional
    public void updateHardware(UUID endpointId, String manufacturer, String model, String serialNumber) {
        repository.findById(endpointId).ifPresent(e -> {
            if (manufacturer != null) e.setManufacturer(manufacturer);
            if (model != null) e.setModel(model);
            if (serialNumber != null) e.setSerialNumber(serialNumber);
            repository.save(e);
        });
    }

    /**
     * Marks an endpoint as having an active ISE session, creating it if this
     * MAC has never been seen. Sets the session start time only on the
     * disconnected-to-connected transition, not on every poll.
     */
    @Transactional
    public void markConnected(String macAddress, String ip) {
        String normalizedMac = normalizeMac(macAddress);
        Endpoint endpoint = repository.findByMacAddress(normalizedMac)
                .orElseGet(() -> Endpoint.builder().macAddress(normalizedMac).build());

        boolean wasConnected = endpoint.isConnected();
        endpoint.setConnected(true);
        if (ip != null) endpoint.setIpAddress(ip);
        if (!wasConnected) endpoint.setSessionStartedAt(Instant.now());
        endpoint.setLastSeenAt(Instant.now());

        repository.save(endpoint);
    }

    /**
     * Marks an endpoint as no longer connected. Its posture history is left
     * untouched. Does nothing if the MAC is unknown.
     */
    @Transactional
    public void markDisconnected(String macAddress) {
        repository.findByMacAddress(normalizeMac(macAddress)).ifPresent(endpoint -> {
            endpoint.setConnected(false);
            endpoint.setLastDisconnectedAt(Instant.now());
            repository.save(endpoint);
        });
    }

    private EndpointResponse toResponse(Endpoint e) {
        return new EndpointResponse(
                e.getId(), e.getMacAddress(), e.getIpAddress(), e.getHostname(),
                e.getOsName(), e.getOsVersion(), e.isConnected(), e.getLastSeenAt()
        );
    }
}
