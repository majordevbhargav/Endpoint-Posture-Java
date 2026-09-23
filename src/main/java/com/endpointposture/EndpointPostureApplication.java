package com.endpointposture;

import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.ise.config.IseProperties;
import com.endpointposture.posture.config.PostureAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the VE Compliance Engine backend.
 *
 * <ul>
 *   <li>{@code @EnableScheduling} switches on the {@code @Scheduled} job worker.</li>
 *   <li>{@code @EnableConfigurationProperties} registers the agent settings
 *       classes so they can be injected.</li>
 *   <li>Spring's default in-memory user is switched off: users come from the
 *       {@code app_user} table and JWTs, not from Spring's generated password.</li>
 * </ul>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({PostureAgentProperties.class, HardwareAgentProperties.class, IseProperties.class})
public class EndpointPostureApplication {

    public static void main(String[] args) {
        SpringApplication.run(EndpointPostureApplication.class, args);
    }
}
