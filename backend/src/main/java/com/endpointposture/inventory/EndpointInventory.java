package com.endpointposture.inventory;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Raw inventory captured during one posture run. Append-only. */
@Entity
@Table(name = "endpoint_inventory")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EndpointInventory {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "assessment_id")
    private UUID assessmentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "listening_ports", columnDefinition = "jsonb")
    private List<Map<String, Object>> listeningPorts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "installed_apps", columnDefinition = "jsonb")
    private List<Map<String, Object>> installedApps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "top_processes", columnDefinition = "jsonb")
    private List<Map<String, Object>> topProcesses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_usage", columnDefinition = "jsonb")
    private Map<String, Object> resourceUsage;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @PrePersist
    void onCreate() {
        if (collectedAt == null) collectedAt = Instant.now();
    }
}