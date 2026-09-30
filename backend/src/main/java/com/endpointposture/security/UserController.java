package com.endpointposture.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** User management. ADMIN only, enforced both in SecurityConfig and here. */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users", description = "Create users, change roles, disable, reset passwords. ADMIN only.")
public class UserController {

    public record UserView(UUID id, String username, Role role, boolean enabled, Instant createdAt) {
        static UserView of(User u) {
            return new UserView(u.getId(), u.getUsername(), u.getRole(), u.isEnabled(), u.getCreatedAt());
        }
    }
    public record CreateUserRequest(@NotBlank @Size(min = 3, max = 64) String username,
                                    @NotBlank @Size(min = 12, max = 128) String password,
                                    @NotNull Role role) {}
    public record UpdateUserRequest(Role role, Boolean enabled) {}
    public record ResetPasswordRequest(@NotBlank @Size(min = 12, max = 128) String password) {}

    private final UserService service;

    public UserController(UserService service) { this.service = service; }

    @Operation(summary = "List users")
    @GetMapping
    public List<UserView> list() { return service.list().stream().map(UserView::of).toList(); }

    @Operation(summary = "Create a user")
    @PostMapping
    public ResponseEntity<UserView> create(@Valid @RequestBody CreateUserRequest req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UserView.of(service.create(req.username(), req.password(), req.role(), auth.getName())));
    }

    @Operation(summary = "Change a user's role and/or enabled flag")
    @PatchMapping("/{id}")
    public UserView update(@PathVariable UUID id, @RequestBody UpdateUserRequest req, Authentication auth) {
        return UserView.of(service.update(id, req.role(), req.enabled(), auth.getName()));
    }

    @Operation(summary = "Reset a user's password")
    @PostMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id,
                                              @Valid @RequestBody ResetPasswordRequest req, Authentication auth) {
        service.resetPassword(id, req.password(), auth.getName());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}