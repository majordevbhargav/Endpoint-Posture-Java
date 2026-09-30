package com.endpointposture.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Data access for {@link User}. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** @return the user with this exact login name, or empty */
    Optional<User> findByUsername(String username);

    /** @return how many enabled users hold this role */
    long countByRoleAndEnabledTrue(Role role);

    /**
     * Counts one failed login atomically. When the count reaches {@code max}
     * the account is locked until {@code until} and the counter restarts at 0.
     * One statement, so concurrent guesses cannot lose an increment.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE app_user
               SET failed_attempts = CASE WHEN failed_attempts + 1 >= CAST(:max AS integer)
                                          THEN 0 ELSE failed_attempts + 1 END,
                   locked_until    = CASE WHEN failed_attempts + 1 >= CAST(:max AS integer)
                                          THEN CAST(:until AS timestamptz) ELSE locked_until END
             WHERE id = :id
            """, nativeQuery = true)
    int registerFailedLogin(@Param("id") UUID id, @Param("max") int max, @Param("until") Instant until);

    /** Clears the counter and any lock (successful login, or admin password reset). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.failedAttempts = 0, u.lockedUntil = null WHERE u.id = :id")
    int clearLoginState(@Param("id") UUID id);
}