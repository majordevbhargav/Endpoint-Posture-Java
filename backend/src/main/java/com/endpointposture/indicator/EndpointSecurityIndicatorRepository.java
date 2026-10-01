package com.endpointposture.indicator;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointSecurityIndicatorRepository extends JpaRepository<EndpointSecurityIndicator, UUID> {
    List<EndpointSecurityIndicator> findByEndpointIdOrderByCollectedAtDesc(UUID endpointId);
    Optional<EndpointSecurityIndicator> findFirstByEndpointIdOrderByCollectedAtDesc(UUID endpointId);
}