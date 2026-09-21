package com.endpointposture.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * Login endpoint. Exchanges a username and password for a JWT.
 *
 * <p>This is the only place tokens are issued. Everything under
 * {@code /api/v1/auth/**} is public (see {@link SecurityConfig}).</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login and JWT issuance. No token required for these routes.")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** Body of {@code POST /api/v1/auth/login}. */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    /** Successful login result: the bearer token plus who it belongs to. */
    public record LoginResponse(String token, String username, String role) {}

    /**
     * Verifies credentials and issues a token.
     *
     * <p>Unknown user, disabled user and wrong password all return the same
     * {@code 401} message, so the response never reveals which usernames exist.</p>
     *
     * @param request the username and password
     * @return {@code 200} with a {@link LoginResponse}, or {@code 401} on any failure
     */
    @Operation(
            summary = "Log in and receive a JWT",
            description = "On success, returns a bearer token valid for the configured "
                    + "expiration window (app.jwt.expiration-minutes). Paste the token "
                    + "into Swagger UI's Authorize button to call protected routes below."
    )
    @SecurityRequirement(name = "")   // overrides the global requirement - this route needs no token
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        var userOpt = userRepository.findByUsername(request.username());

        if (userOpt.isEmpty() || !userOpt.get().isEnabled()
                || !passwordEncoder.matches(request.password(), userOpt.get().getPasswordHash())) {
            return ResponseEntity.status(401).body("Invalid username or password");
        }

        User user = userOpt.get();
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        return ResponseEntity.ok(new LoginResponse(token, user.getUsername(), user.getRole().name()));
    }
}
