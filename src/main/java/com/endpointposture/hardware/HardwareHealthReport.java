package com.endpointposture.hardware;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One hardware-health collection run for one endpoint.
 *
 * <p>Maps to the {@code hardware_health} table. Append-only, same as
 * {@link com.endpointposture.posture.Assessment} — every run is a new
 * row, nothing is ever updated in place, so a trend over time is just a
 * query, not a separate history mechanism.</p>
 *
 * <p>A run that failed before anything could be scored is still recorded:
 * {@link #succeeded} is {@code false}, the score/band fields are
 * {@code null} (not zero — zero would look like a genuinely critical
 * reading), and {@link #errorMessage} carries the reason. This mirrors
 * {@code assessment.status = ERROR} on the posture side.</p>
 */
@Entity
@Table(name = "hardware_health")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HardwareHealthReport {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "job_id")
    private UUID jobId;

    private String manufacturer;
    private String model;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "bios_version")
    private String biosVersion;

    // Nullable now: a failed collection run has no scores at all, not
    // zero scores. See the class javadoc.
    @Column(name = "cpu_score")
    private Integer cpuScore;

    @Column(name = "memory_score")
    private Integer memoryScore;

    @Column(name = "storage_score")
    private Integer storageScore;

    /** Nullable on purpose — see the schema comment: no battery means "not applicable", not zero. */
    @Column(name = "battery_score")
    private Integer batteryScore;

    @Column(name = "overall_score")
    private Integer overallScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_band")
    private HardwareBand overallBand;

    @Column(name = "hardware_event_count")
    private Integer hardwareEventCount;

    @Column(name = "warranty_status")
    private String warrantyStatus;

    @Column(name = "warranty_days_remaining")
    private Integer warrantyDaysRemaining;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_report", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> rawReport;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    /** Whether this run actually produced scores. {@code false} means collection/scoring failed — see {@link #errorMessage}. */
    @Builder.Default
    @Column(nullable = false)
    private boolean succeeded = true;

    /** Why the run failed, when {@link #succeeded} is {@code false}. {@code null} on a successful run. */
    @Column(name = "error_message")
    private String errorMessage;

    @PrePersist
    void onCreate() {
        if (collectedAt == null) collectedAt = Instant.now();
    }
}