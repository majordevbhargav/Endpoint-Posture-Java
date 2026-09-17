package com.endpointposture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * {@link UserDetailsServiceAutoConfiguration} is explicitly excluded:
 * authentication in this project is handled entirely by
 * {@link com.endpointposture.security.AuthController}, which looks users
 * up directly via {@link com.endpointposture.security.UserRepository} and
 * verifies passwords with {@link org.springframework.security.crypto.password.PasswordEncoder}
 * - it never goes through Spring Security's {@code AuthenticationManager}/
 * {@code UserDetailsService} machinery. Without this exclusion, Spring Boot
 * auto-configures its own default in-memory user (a random UUID password
 * logged on every startup) that this application never actually uses,
 * which is confusing dead weight rather than a real second auth path.
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableScheduling
public class EndpointPostureApplication {
    public static void main(String[] args) {
        SpringApplication.run(EndpointPostureApplication.class, args);
    }
}