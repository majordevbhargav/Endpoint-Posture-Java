package com.endpointposture.hardware;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HardwareRecommendationRepository extends JpaRepository<HardwareRecommendation, UUID> {

    List<HardwareRecommendation> findByHardwareHealthId(UUID hardwareHealthId);
}