package com.endpointposture.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public List<User> list() {
        return users.findAll().stream()
                .sorted((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername())).toList();
    }

    @Transactional
    public User create(String username, String password, Role role, String actor) {
        String name = username.trim();
        if (users.findByUsername(name).isPresent()) {
            throw new IllegalStateException("Username '" + name + "' already exists");
        }
        User saved = users.save(User.builder()
                .username(name).passwordHash(encoder.encode(password)).role(role).enabled(true).build());
        log.info("User '{}' created with role {} by {}", name, role, actor);
        return saved;
    }

    @Transactional
    public User update(UUID id, Role newRole, Boolean newEnabled, String actor) {
        User u = users.findById(id).orElseThrow(() -> new IllegalArgumentException("No such user"));
        Role role = newRole != null ? newRole : u.getRole();
        boolean enabled = newEnabled != null ? newEnabled : u.isEnabled();

        boolean losesAdmin = u.isEnabled() && u.getRole() == Role.ADMIN && (role != Role.ADMIN || !enabled);
        if (losesAdmin) {
            if (u.getUsername().equals(actor)) {
                throw new IllegalArgumentException("You cannot demote or disable your own account");
            }
            if (users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
                throw new IllegalStateException("At least one enabled ADMIN must remain");
            }
        }
        u.setRole(role);
        u.setEnabled(enabled);
        log.info("User '{}' set to role={} enabled={} by {}", u.getUsername(), role, enabled, actor);
        return users.save(u);
    }

    @Transactional
    public void resetPassword(UUID id, String password, String actor) {
        User u = users.findById(id).orElseThrow(() -> new IllegalArgumentException("No such user"));
        u.setPasswordHash(encoder.encode(password));
        users.save(u);
        log.info("Password reset for '{}' by {}", u.getUsername(), actor);
    }

    @Transactional
    public void delete(UUID id, String actor) {
        User u = users.findById(id).orElseThrow(() -> new IllegalArgumentException("No such user"));
        if (u.getUsername().equals(actor)) {
            throw new IllegalArgumentException("You cannot delete your own account");
        }
        if (u.isEnabled() && u.getRole() == Role.ADMIN && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new IllegalStateException("At least one enabled ADMIN must remain");
        }
        users.delete(u);
        log.info("User '{}' deleted by {}", u.getUsername(), actor);
    }
}