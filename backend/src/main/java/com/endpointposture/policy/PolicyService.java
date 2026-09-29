package com.endpointposture.policy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for required and blocked applications.
 *
 * <p>Read by {@link com.endpointposture.job.JobWorker} (handed to the agent),
 * by {@link com.endpointposture.inventory.InventoryController} (to label the
 * Installed Software page) and by the policy API. Nothing here calls ISE.</p>
 *
 * <p>Editing creates a new version and deactivates the old one; old versions
 * are kept forever so past assessments stay explainable.</p>
 */
@Service
public class PolicyService {

    private static final int MAX_PATTERN_LENGTH = 200;

    /**
     * Read model of one policy version.
     *
     * @param requiredApps patterns that must be installed
     * @param blockedApps  patterns that must not be installed
     */
    public record PolicySnapshot(UUID id, String name, int version,
                                 List<String> requiredApps, List<String> blockedApps,
                                 String createdBy, Instant createdAt) {}

    private final AppPolicyRepository policies;
    private final AppPolicyRuleRepository rules;

    public PolicyService(AppPolicyRepository policies, AppPolicyRuleRepository rules) {
        this.policies = policies;
        this.rules = rules;
    }

    /**
     * @return the active policy
     * @throws IllegalStateException if none is active (fail loudly rather than guess defaults)
     */
    @Transactional(readOnly = true)
    public PolicySnapshot getActive() {
        AppPolicy active = policies.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active application policy exists"));
        return toSnapshot(active);
    }

    /** @return every policy version, newest first */
    @Transactional(readOnly = true)
    public List<PolicySnapshot> history() {
        return policies.findAllByOrderByVersionDesc().stream().map(this::toSnapshot).toList();
    }

    /**
     * Replaces the active policy with a new version holding the given lists.
     *
     * @param operator the authenticated caller, stored as {@code created_by}
     * @throws IllegalArgumentException if a pattern is invalid, or appears in both lists
     */
    @Transactional
    public PolicySnapshot replaceActive(List<String> required, List<String> blocked, String operator) {
        List<String> req = clean(required);
        List<String> blk = clean(blocked);

        Set<String> requiredLower = new HashSet<>();
        for (String r : req) requiredLower.add(r.toLowerCase(Locale.ROOT));
        for (String b : blk) {
            if (requiredLower.contains(b.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("'" + b + "' cannot be both required and blocked");
            }
        }

        var current = policies.findByActiveTrue();
        int nextVersion = policies.findFirstByOrderByVersionDesc().map(p -> p.getVersion() + 1).orElse(1);
        String name = current.map(AppPolicy::getName).orElse("Application policy");

        // Flush the deactivation first: Hibernate runs inserts before updates,
        // which would otherwise briefly create two active rows and trip the
        // single-active unique index.
        current.ifPresent(c -> {
            c.setActive(false);
            policies.saveAndFlush(c);
        });

        AppPolicy created = policies.save(AppPolicy.builder()
                .name(name)
                .version(nextVersion)
                .active(true)
                .createdBy(operator)
                .build());

        for (String pattern : req) {
            rules.save(AppPolicyRule.builder()
                    .policyId(created.getId()).appPattern(pattern).ruleType(AppPolicyRule.RuleType.REQUIRED).build());
        }
        for (String pattern : blk) {
            rules.save(AppPolicyRule.builder()
                    .policyId(created.getId()).appPattern(pattern).ruleType(AppPolicyRule.RuleType.BLOCKED).build());
        }

        return new PolicySnapshot(created.getId(), created.getName(), created.getVersion(),
                sorted(req), sorted(blk), created.getCreatedBy(), created.getCreatedAt());
    }

    /**
     * Trims, drops blanks, removes case-insensitive duplicates, and rejects
     * characters that would break the agent's command-line hand-off.
     */
    static List<String> clean(List<String> input) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (input == null) return out;
        for (String raw : input) {
            if (raw == null) continue;
            String p = raw.trim();
            if (p.isEmpty()) continue;
            if (p.length() > MAX_PATTERN_LENGTH) {
                throw new IllegalArgumentException("Pattern is longer than " + MAX_PATTERN_LENGTH + " characters");
            }
            // '|' is the delimiter used to hand the list to the agent; '"' breaks
            // Windows argument quoting; control characters are never valid.
            if (p.indexOf('|') >= 0 || p.indexOf('"') >= 0 || p.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Pattern '" + p + "' contains an unsupported character (| or \")");
            }
            if (seen.add(p.toLowerCase(Locale.ROOT))) out.add(p);
        }
        return out;
    }

    private PolicySnapshot toSnapshot(AppPolicy p) {
        List<String> req = new ArrayList<>();
        List<String> blk = new ArrayList<>();
        for (AppPolicyRule r : rules.findByPolicyId(p.getId())) {
            (r.getRuleType() == AppPolicyRule.RuleType.REQUIRED ? req : blk).add(r.getAppPattern());
        }
        return new PolicySnapshot(p.getId(), p.getName(), p.getVersion(),
                sorted(req), sorted(blk), p.getCreatedBy(), p.getCreatedAt());
    }

    private static List<String> sorted(List<String> in) {
        return in.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}