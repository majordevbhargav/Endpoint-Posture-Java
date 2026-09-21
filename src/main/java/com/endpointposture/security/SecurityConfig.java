package com.endpointposture.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Central Spring Security setup: which routes are public, which need a
 * token, how requests are authenticated, and the first-run admin account.
 *
 * <p>The API is stateless - no sessions, no cookies. Every protected request
 * must carry either a JWT ({@link JwtAuthFilter}) or, for the posture
 * ingestion route only, the agent API key ({@link PostureApiKeyFilter}).</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final PostureApiKeyFilter postureApiKeyFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, PostureApiKeyFilter postureApiKeyFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.postureApiKeyFilter = postureApiKeyFilter;
    }

    /** BCrypt encoder used to hash and verify user passwords. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Builds the request-security rules.
     *
     * <p>Public: login, health, Swagger UI. Posture ingestion
     * ({@code POST /api/v1/posture}) accepts an admin JWT or the agent key.
     * Everything else requires an authenticated user.</p>
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless JWT API, no cookies/forms
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/actuator/health",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**")
                        .permitAll()
                        // Ingestion is open to the agent key (ROLE_AGENT) and to
                        // admins (handy for testing from Swagger). Because the
                        // agent role is granted only for this exact route, the
                        // key cannot read or change anything else.
                        .requestMatchers(HttpMethod.POST, "/api/v1/posture")
                        .hasAnyRole("AGENT", "ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(postureApiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Seeds one admin user on startup if the app_user table is empty.
     * This is the whole of "RBAC" for Stage 1, deliberately: enough that
     * every route (except /auth and health) requires a real bearer token,
     * without building out multi-role management before there's more than
     * one role actually enforced differently anywhere in the app.
     *
     * @param seedUsername initial admin login ({@code app.seed-admin.username})
     * @param seedPassword initial admin password ({@code app.seed-admin.password});
     *                     change it before this leaves your laptop
     */
    @Bean
    public CommandLineRunner seedAdmin(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed-admin.username}") String seedUsername,
            @Value("${app.seed-admin.password}") String seedPassword) {
        return args -> {
            if (userRepository.count() == 0) {
                User admin = User.builder()
                        .username(seedUsername)
                        .passwordHash(passwordEncoder.encode(seedPassword))
                        .role(Role.ADMIN)
                        .enabled(true)
                        .build();
                userRepository.save(admin);
                System.out.println(">>> Seeded initial admin user: " + seedUsername
                        + " (change SEED_ADMIN_PASSWORD before this leaves your laptop)");
            }
        };
    }
}
