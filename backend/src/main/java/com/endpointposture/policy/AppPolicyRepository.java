package com.endpointposture.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Data access for {@link AppPolicy}. */
public interface AppPolicyRepository extends JpaRepository<AppPolicy, UUID> {

    /** @return the single active policy, or empty if none exists */
    Optional<AppPolicy> findByActiveTrue();

    /** @return the policy with the highest version number (used to pick the next version) */
    Optional<AppPolicy> findFirstByOrderByVersionDesc();

    /** @return every policy version, newest first */
    List<AppPolicy> findAllByOrderByVersionDesc();
}