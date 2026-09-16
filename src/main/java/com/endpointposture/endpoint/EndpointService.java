package com.endpointposture.endpoint;

import com.endpointposture.endpoint.dto.EndpointResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class EndpointService {

    private final EndpointRepository repository;

    public EndpointService(EndpointRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EndpointResponse> listAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public EndpointResponse getById(UUID id) {
        return repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new EndpointNotFoundException(id.toString()));
    }

    /**
     * Upsert-by-MAC — this is the real ingestion path. Every collector
     * (posture agent, ISE watcher) calls this, never a bare save(), so
     * the MAC uniqueness constraint is the single source of truth for
     * "is this a device we already know about."
     */
    @Transactional
    public Endpoint upsertByMac(String macAddress, String ip, String hostname, String os, String osVersion) {
        String normalizedMac = macAddress.toUpperCase(Locale.ROOT);

        Endpoint endpoint = repository.findByMacAddress(normalizedMac)
                .orElseGet(() -> Endpoint.builder().macAddress(normalizedMac).build());

        if (ip != null) endpoint.setIpAddress(ip);
        if (hostname != null) endpoint.setHostname(hostname);
        if (os != null) endpoint.setOsName(os);
        if (osVersion != null) endpoint.setOsVersion(osVersion);
        endpoint.setLastSeenAt(Instant.now());

        return repository.save(endpoint);
    }

    @Transactional
    public void markConnected(String macAddress, String ip) {
        String normalizedMac = macAddress.toUpperCase(Locale.ROOT);
        Endpoint endpoint = repository.findByMacAddress(normalizedMac)
                .orElseGet(() -> Endpoint.builder().macAddress(normalizedMac).build());

        boolean wasConnected = endpoint.isConnected();
        endpoint.setConnected(true);
        if (ip != null) endpoint.setIpAddress(ip);
        if (!wasConnected) endpoint.setSessionStartedAt(Instant.now());
        endpoint.setLastSeenAt(Instant.now());

        repository.save(endpoint);
    }

    @Transactional
    public void markDisconnected(String macAddress) {
        repository.findByMacAddress(macAddress.toUpperCase(Locale.ROOT)).ifPresent(endpoint -> {
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