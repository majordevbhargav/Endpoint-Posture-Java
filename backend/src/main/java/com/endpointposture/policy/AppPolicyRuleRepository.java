package com.endpointposture.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Data access for {@link AppPolicyRule}. */
public interface AppPolicyRuleRepository extends JpaRepository<AppPolicyRule, UUID> {

    List<AppPolicyRule> findByPolicyId(UUID policyId);
}