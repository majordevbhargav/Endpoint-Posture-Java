package com.endpointposture.policy;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One version of the application policy.
 *
 * <p>Maps to {@code app_policy} ({@code V13__create_app_policy.sql}). Rows are
 * never edited to change their rules: a change inserts a new version and
 * deactivates the previous one. Only {@link #active} is ever updated.</p>
 */
@Entity
@Table(name = "app_policy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppPolicy {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Monotonic version number across all policies. */
    @Column(nullable = false)
    private int version;

    /** At most one row is active (enforced by a partial unique index). */
    @Column(nullable = false)
    private boolean active;

    /** Username of the operator who created this version ({@code system} for the seed). */
    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}