// src/main/java/com/endpointposture/session/EndpointSessionLog.java
package com.endpointposture.session;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "endpoint_session_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EndpointSessionLog {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private SessionEventType eventType;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "event_at", nullable = false)
    private Instant eventAt;

    @PrePersist
    void onCreate() {
        if (eventAt == null) eventAt = Instant.now();
    }
}