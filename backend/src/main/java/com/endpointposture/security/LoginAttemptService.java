package com.endpointposture.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Login lockout: after {@code max-attempts} consecutive wrong passwords an
 * account is refused for {@code lock-minutes}. Only wrong passwords for an
 * existing, enabled user are counted; attempts made while locked are not, so
 * an attacker cannot extend a lock indefinitely.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final UserRepository users;
    private final int maxAttempts;
    private final Duration lockDuration;

    public LoginAttemptService(UserRepository users,
                               @Value("${app.security.login.max-attempts:5}") int maxAttempts,
                               @Value("${app.security.login.lock-minutes:5}") long lockMinutes) {
        this.users = users;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.lockDuration = Duration.ofMinutes(Math.max(1, lockMinutes));
    }

    /** @return true while the user's lock has not expired */
    public boolean isLocked(User user) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now());
    }

    /** Records one wrong password; logs when that tips the account into a lock. */
    @Transactional
    public void recordFailure(User user) {
        users.registerFailedLogin(user.getId(), maxAttempts, Instant.now().plus(lockDuration));
        users.findById(user.getId()).filter(this::isLocked).ifPresent(u ->
                log.warn("Account '{}' locked until {} after {} failed logins",
                        u.getUsername(), u.getLockedUntil(), maxAttempts));
    }

    /** Resets the counter after a good login (skips the write when there is nothing to clear). */
    @Transactional
    public void recordSuccess(User user) {
        if (user.getFailedAttempts() > 0 || user.getLockedUntil() != null) {
            users.clearLoginState(user.getId());
        }
    }
}