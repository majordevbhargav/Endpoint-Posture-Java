package com.endpointposture.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pins lockout-prevention rules (self-protection, last admin) and unlock-on-reset/re-enable. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock UserRepository users;
    @Mock PasswordEncoder encoder;

    UserService service;

    User alice;
    User bob;
    User viewer;

    @BeforeEach
    void setUp() {
        service = new UserService(users, encoder);

        alice = user("alice", Role.ADMIN, true);
        bob = user("bob", Role.ADMIN, true);
        viewer = user("vera", Role.VIEWER, true);

        when(users.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(users.findById(bob.getId())).thenReturn(Optional.of(bob));
        when(users.findById(viewer.getId())).thenReturn(Optional.of(viewer));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(users.countByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(2L);
        when(encoder.encode(any())).thenAnswer(i -> "hash:" + i.getArgument(0));
    }

    private User user(String name, Role role, boolean enabled) {
        return User.builder().id(UUID.randomUUID()).username(name).passwordHash("x")
                .role(role).enabled(enabled).build();
    }

    // --- create ---

    @Test
    void createHashesThePasswordAndTrimsTheName() {
        when(users.findByUsername("carol")).thenReturn(Optional.empty());

        User created = service.create("  carol ", "a-long-password-1", Role.ANALYST, "alice");

        assertEquals("carol", created.getUsername());
        assertEquals("hash:a-long-password-1", created.getPasswordHash());
        assertEquals(Role.ANALYST, created.getRole());
        assertTrue(created.isEnabled());
    }

    @Test
    void createRejectsADuplicateUsername() {
        when(users.findByUsername("bob")).thenReturn(Optional.of(bob));
        assertThrows(IllegalStateException.class,
                () -> service.create("bob", "a-long-password-1", Role.VIEWER, "alice"));
        verify(users, never()).save(any());
    }

    // --- update ---

    @Test
    void adminCannotDemoteThemselves() {
        assertThrows(IllegalArgumentException.class,
                () -> service.update(alice.getId(), Role.VIEWER, null, "alice"));
        assertEquals(Role.ADMIN, alice.getRole());
        verify(users, never()).save(any());
    }

    @Test
    void adminCannotDisableThemselves() {
        assertThrows(IllegalArgumentException.class,
                () -> service.update(alice.getId(), null, false, "alice"));
        assertTrue(alice.isEnabled());
    }

    @Test
    void anotherAdminCanDemoteAnAdminWhenTwoExist() {
        User result = service.update(bob.getId(), Role.OPERATOR, null, "alice");
        assertEquals(Role.OPERATOR, result.getRole());
    }

    @Test
    void lastEnabledAdminCannotBeDemotedByAnotherActor() {
        when(users.countByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(1L);
        assertThrows(IllegalStateException.class,
                () -> service.update(bob.getId(), Role.VIEWER, null, "someone-else"));
        assertEquals(Role.ADMIN, bob.getRole());
    }

    @Test
    void lastEnabledAdminCannotBeDisabledByAnotherActor() {
        when(users.countByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(1L);
        assertThrows(IllegalStateException.class,
                () -> service.update(bob.getId(), null, false, "someone-else"));
        assertTrue(bob.isEnabled());
    }

    @Test
    void changingANonAdminNeverTriggersTheAdminGuards() {
        when(users.countByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(1L);
        User result = service.update(viewer.getId(), Role.ANALYST, false, "alice");
        assertEquals(Role.ANALYST, result.getRole());
        assertFalse(result.isEnabled());
    }

    @Test
    void updatingWithNoChangeOnYourOwnAccountIsAllowed() {
        User result = service.update(alice.getId(), Role.ADMIN, true, "alice");
        assertEquals(Role.ADMIN, result.getRole());
    }

    @Test
    void unknownUserIsRejected() {
        UUID missing = UUID.randomUUID();
        when(users.findById(missing)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.update(missing, Role.VIEWER, null, "alice"));
    }

    @Test
    void reEnablingADisabledUserClearsItsLock() {
        viewer.setEnabled(false);
        viewer.setFailedAttempts(3);
        viewer.setLockedUntil(Instant.now().plusSeconds(300));

        User result = service.update(viewer.getId(), null, true, "alice");

        assertTrue(result.isEnabled());
        assertEquals(0, result.getFailedAttempts());
        assertNull(result.getLockedUntil());
    }

    // --- resetPassword ---

    @Test
    void resetPasswordStoresAFreshHash() {
        service.resetPassword(viewer.getId(), "another-long-password", "alice");
        assertEquals("hash:another-long-password", viewer.getPasswordHash());
        verify(users).save(viewer);
    }

    @Test
    void resetPasswordUnlocksALockedAccount() {
        viewer.setFailedAttempts(4);
        viewer.setLockedUntil(Instant.now().plusSeconds(300));

        service.resetPassword(viewer.getId(), "another-long-password", "alice");

        assertEquals(0, viewer.getFailedAttempts());
        assertNull(viewer.getLockedUntil());
    }

    // --- delete ---

    @Test
    void cannotDeleteYourself() {
        assertThrows(IllegalArgumentException.class, () -> service.delete(alice.getId(), "alice"));
        verify(users, never()).delete(any());
    }

    @Test
    void cannotDeleteTheLastEnabledAdmin() {
        when(users.countByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> service.delete(bob.getId(), "someone-else"));
        verify(users, never()).delete(any());
    }

    @Test
    void canDeleteAnAdminWhenAnotherEnabledAdminRemains() {
        service.delete(bob.getId(), "alice");
        verify(users).delete(bob);
    }

    @Test
    void canDeleteANonAdminEvenWhenOnlyOneAdminExists() {
        when(users.countByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(1L);
        service.delete(viewer.getId(), "alice");
        verify(users).delete(viewer);
    }

    @Test
    void deletingAnUnknownUserIsRejected() {
        UUID missing = UUID.randomUUID();
        when(users.findById(missing)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.delete(missing, "alice"));
    }
}