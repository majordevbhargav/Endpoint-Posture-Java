package com.endpointposture.policy;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One required or blocked application pattern belonging to an
 * {@link AppPolicy}. Matching is a case-insensitive substring match against
 * the installed program's display name, the same rule the agent uses.
 */
@Entity
@Table(name = "app_policy_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppPolicyRule {

    public enum RuleType { REQUIRED, BLOCKED }

    @Id
    @GeneratedValue
    private UUID id;

    // Plain UUID column, same convention as the other FK columns in this codebase.
    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "app_pattern", nullable = false)
    private String appPattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private RuleType ruleType;
}