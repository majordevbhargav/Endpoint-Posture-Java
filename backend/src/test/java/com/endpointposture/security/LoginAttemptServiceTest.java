package com.endpointposture.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    @Mock UserRepository users;
    LoginAttemptService service;
    User user;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService(users, 5, 5);
        user = User.builder().id(UUID.randomUUID()).username("alice").passwordHash("x")
                .role(Role.VIEWER).enabled(true).build();
    }

    @Test
    void notLockedWhenNoLockIsSet() {
        assertFalse(service.isLocked(user));
    }

    @Test
    void lockedWhileLockedUntilIsInTheFuture() {
        user.setLockedUntil(Instant.now().plusSeconds(60));
        assertTrue(service.isLocked(user));
    }

    @Test
    void anExpiredLockNoLongerBlocks() {
        user.setLockedUntil(Instant.now().minusSeconds(1));
        assertFalse(service.isLocked(user));
    }

    @Test
    void failureIsCountedWithTheConfiguredThresholdAndLockWindow() {
        service.recordFailure(user);

        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(users).registerFailedLogin(eq(user.getId()), eq(5), until.capture());
        assertTrue(until.getValue().isAfter(Instant.now().plusSeconds(4 * 60)));
        assertTrue(until.getValue().isBefore(Instant.now().plusSeconds(6 * 60)));
    }

    @Test
    void successWithNothingToClearWritesNothing() {
        service.recordSuccess(user);
        verify(users, never()).clearLoginState(any());
    }

    @Test
    void successClearsAPendingCounter() {
        user.setFailedAttempts(3);
        service.recordSuccess(user);
        verify(users).clearLoginState(user.getId());
    }

    @Test
    void successClearsAnExpiredLock() {
        user.setLockedUntil(Instant.now().minusSeconds(30));
        service.recordSuccess(user);
        verify(users).clearLoginState(user.getId());
    }
}