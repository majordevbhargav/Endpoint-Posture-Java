package com.endpointposture.diagnostic;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One diagnostic run against one endpoint. Maps to {@code endpoint_diagnostic}
 * ({@code V16}). Append-only, like assessments and hardware reports.
 *
 * <p>{@link #score} and {@link #band} are {@code null} (never zero) when the
 * run did not produce measurements: {@code WINRM_UNAVAILABLE} (endpoint could
 * not be reached for remote probing) or {@code FAILED} (the job itself failed).</p>
 */
@Entity
@Table(name = "endpoint_diagnostic")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndpointDiagnostic {

    /** How the run ended. */
    public enum Status { OK, WINRM_UNAVAILABLE, FAILED }

    /** Same thresholds and names as hardware bands, so the UI badge is shared. */
    public enum Band { HEALTHY, WARNING, DEGRADED, CRITICAL }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "job_id")
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private Integer score;

    @Enumerated(EnumType.STRING)
    private Band band;

    /** Why points were lost: [{check, points, reason}]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> deductions;

    /** Everything the agent measured, as submitted. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_report", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> rawReport;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @PrePersist
    void onCreate() {
        if (collectedAt == null) collectedAt = Instant.now();
    }
}