package com.endpointposture.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * Login endpoint. Exchanges a username and password for a JWT.
 *
 * <p>Unknown user, disabled user, locked user and wrong password all return the
 * same {@code 401} body, so the response never reveals which usernames exist or
 * which are locked. A password check always runs (against a dummy hash when
 * there is no usable account) so timing does not reveal it either.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login and JWT issuance. No token required for these routes.")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String BAD_LOGIN = "Invalid username or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;
    private final String dummyHash;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          JwtService jwtService, LoginAttemptService loginAttempts) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttempts = loginAttempts;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    /** Body of {@code POST /api/v1/auth/login}. */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    /** Successful login result: the bearer token plus who it belongs to. */
    public record LoginResponse(String token, String username, String role) {}

    @Operation(
            summary = "Log in and receive a JWT",
            description = "On success, returns a bearer token valid for the configured "
                    + "expiration window (app.jwt.expiration-minutes). After repeated wrong "
                    + "passwords the account is locked for a few minutes; a locked account "
                    + "gets the same 401 as a wrong password."
    )
    @SecurityRequirement(name = "")   // overrides the global requirement - this route needs no token
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByUsername(request.username()).orElse(null);

        // Always pay for one BCrypt check so timing is the same for every outcome.
        boolean passwordOk = passwordEncoder.matches(
                request.password(), user != null ? user.getPasswordHash() : dummyHash);

        if (user == null) {
            return unauthorized();
        }
        if (loginAttempts.isLocked(user)) {
            log.info("Login refused for locked account '{}'", user.getUsername());
            return unauthorized();
        }
        if (!user.isEnabled()) {
            return unauthorized();
        }
        if (!passwordOk) {
            loginAttempts.recordFailure(user);
            return unauthorized();
        }

        loginAttempts.recordSuccess(user);
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        return ResponseEntity.ok(new LoginResponse(token, user.getUsername(), user.getRole().name()));
    }

    private ResponseEntity<String> unauthorized() {
        return ResponseEntity.status(401).body(BAD_LOGIN);
    }
}