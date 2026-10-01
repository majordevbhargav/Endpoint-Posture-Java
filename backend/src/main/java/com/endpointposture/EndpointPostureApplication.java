package com.endpointposture;

import com.endpointposture.diagnostic.config.DiagnosticAgentProperties;
import com.endpointposture.hardware.config.HardwareAgentProperties;
import com.endpointposture.ise.config.IseProperties;
import com.endpointposture.indicator.config.SecurityIndicatorAgentProperties;
import com.endpointposture.posture.config.PostureAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({
        PostureAgentProperties.class,
        HardwareAgentProperties.class,
        DiagnosticAgentProperties.class,
        SecurityIndicatorAgentProperties.class,
        IseProperties.class
})
public class EndpointPostureApplication {

    public static void main(String[] args) {
        SpringApplication.run(EndpointPostureApplication.class, args);
    }
}
