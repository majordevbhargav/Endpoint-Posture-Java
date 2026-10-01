package com.endpointposture.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Central Spring Security setup. The API is stateless: every protected request
 * carries a JWT ({@link JwtAuthFilter}) or, for the three agent ingestion routes
 * only, the shared agent API key ({@link PostureApiKeyFilter}). Finer rules use
 * {@code @PreAuthorize} on controllers; admin-only routes are also listed here
 * (defence in depth).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final PostureApiKeyFilter postureApiKeyFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, PostureApiKeyFilter postureApiKeyFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.postureApiKeyFilter = postureApiKeyFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless JWT API, no cookies/forms
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/actuator/health",
                                "/error",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs",
                                "/v3/api-docs/**")
                        .permitAll()
                        // Prometheus scrape endpoint: authenticated ADMIN only.
                        .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                        // Agent key (ROLE_AGENT) or admin, for these three exact routes only.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/posture", "/api/v1/hardware-health", "/api/v1/diagnostics")
                        .hasAnyRole("AGENT", "ADMIN")
                        // Changing what counts as compliant is an admin decision.
                        .requestMatchers(HttpMethod.PUT, "/api/v1/policy/**").hasRole("ADMIN")
                        // Warranty data feeds hardware health, so uploads are admin only.
                        .requestMatchers(HttpMethod.POST, "/api/v1/warranty/upload").hasRole("ADMIN")
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(postureApiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** Seeds one admin when {@code app_user} is empty (first run only). */
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