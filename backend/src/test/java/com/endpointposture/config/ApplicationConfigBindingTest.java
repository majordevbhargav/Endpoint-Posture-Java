package com.endpointposture.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationConfigBindingTest {

    @Test
    void testSecurityLoginPropertiesBindDirectlyUnderApp() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        assertNotNull(props, "Properties loaded from application.yml should not be null");

        // Verify app.security.login keys exist directly under app
        assertEquals("5", props.getProperty("app.security.login.max-attempts"));
        assertEquals("5", props.getProperty("app.security.login.lock-minutes"));

        // Verify they are NOT under app.jobs
        assertNull(props.getProperty("app.jobs.security.login.max-attempts"),
                "app.jobs.security.login.max-attempts should not exist");
        assertNull(props.getProperty("app.jobs.security.login.lock-minutes"),
                "app.jobs.security.login.lock-minutes should not exist");

        // Verify app.jobs keys are still under app.jobs
        assertEquals("true", props.getProperty("app.jobs.workers.enabled"));
        assertEquals("true", props.getProperty("app.jobs.recheck.enabled"));
        assertEquals("4", props.getProperty("app.jobs.recheck.posture-hours"));
        assertEquals("24", props.getProperty("app.jobs.recheck.hardware-hours"));
        assertEquals("6", props.getProperty("app.jobs.recheck.failure-backoff-hours"));
        assertEquals("300000", props.getProperty("app.jobs.recheck.sweep-interval-ms"));
    }
}
