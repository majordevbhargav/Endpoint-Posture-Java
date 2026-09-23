package com.endpointposture.hardware;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HardwareHealthRepository extends JpaRepository<HardwareHealthReport, UUID> {

    List<HardwareHealthReport> findByEndpointIdOrderByCollectedAtDesc(UUID endpointId);

    Optional<HardwareHealthReport> findFirstByEndpointIdOrderByCollectedAtDesc(UUID endpointId);
}