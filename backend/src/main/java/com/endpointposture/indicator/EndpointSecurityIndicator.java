package com.endpointposture.indicator;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One security-indicator sampling run. Append-only. Score-like fields are null, never zero, when nothing was measured. */
@Entity @Table(name = "endpoint_security_indicator")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EndpointSecurityIndicator {

    public enum Status { OK, WINRM_UNAVAILABLE, FAILED }
    /** Ordered least to most severe; the analyzer relies on ordinal(). */
    public enum RiskLevel { NONE, LOW, MEDIUM, HIGH }

    @Id @GeneratedValue private UUID id;
    @Column(name = "endpoint_id", nullable = false) private UUID endpointId;
    @Column(name = "job_id") private UUID jobId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Enumerated(EnumType.STRING) @Column(name = "risk_level") private RiskLevel riskLevel;

    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> findings;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    private Map<String, Object> summary;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_report", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> rawReport;

    @Column(name = "error_message") private String errorMessage;
    @Column(name = "collected_at", nullable = false) private Instant collectedAt;

    @PrePersist void onCreate() { if (collectedAt == null) collectedAt = Instant.now(); }
}