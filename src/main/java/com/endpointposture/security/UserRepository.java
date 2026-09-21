package com.endpointposture.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link User}.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Looks a user up by login name.
     *
     * @param username the login name (exact match)
     * @return the user, or empty if no such login exists
     */
    Optional<User> findByUsername(String username);
}
