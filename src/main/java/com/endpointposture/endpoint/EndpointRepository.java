package com.endpointposture.endpoint;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {
    Optional<Endpoint> findByMacAddress(String macAddress);
    List<Endpoint> findAllByConnectedTrue();
}