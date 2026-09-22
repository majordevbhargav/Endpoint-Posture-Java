package com.endpointposture.hardware;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One proactive action item derived from a {@link HardwareHealthReport}
 * (e.g. "Storage health degraded — plan replacement"). Zero or more per
 * report, mirroring how {@code check_result} rows attach to an
 * {@code assessment}.
 */
@Entity
@Table(name = "hardware_recommendation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HardwareRecommendation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "hardware_health_id", nullable = false)
    private UUID hardwareHealthId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecommendationPriority priority;

    @Column(nullable = false)
    private String area;

    @Column(nullable = false)
    private String action;

    public enum RecommendationPriority { LOW, MEDIUM, HIGH }
}