// src/main/java/com/endpointposture/session/EndpointSessionLogRepository.java
package com.endpointposture.session;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface EndpointSessionLogRepository extends JpaRepository<EndpointSessionLog, UUID> {
    List<EndpointSessionLog> findByEndpointIdOrderByEventAtDesc(UUID endpointId);
}