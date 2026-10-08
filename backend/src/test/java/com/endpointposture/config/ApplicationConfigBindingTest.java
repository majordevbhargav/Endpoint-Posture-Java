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

        // S12 & hardening assertions
        assertEquals("4", props.getProperty("spring.task.scheduling.pool.size"));
        assertTrue(props.keySet().stream().noneMatch(k -> k.toString().startsWith("app.task.")),
                "No app.task.* property should exist; task scheduling belongs under spring.task.scheduling");
        assertEquals("120000", props.getProperty("spring.datasource.hikari.keepalive-time"));
        assertEquals("900000", props.getProperty("spring.datasource.hikari.max-lifetime"));
        assertEquals("15000", props.getProperty("spring.datasource.hikari.connection-timeout"));
        assertEquals("500", props.getProperty("app.jobs.recheck.max-per-sweep"));
        assertEquals("5", props.getProperty("app.ise.touch-min-minutes"));
        assertEquals("2000", props.getProperty("app.api.fleet-list-max-endpoints"));

        // Phase B hardening assertions
        assertEquals("false", props.getProperty("app.docs.public"));
        assertEquals("false", props.getProperty("app.security.trust-forwarded-for"));
        assertEquals("true", props.getProperty("app.security.login.rate-limit.enabled"));
        assertEquals("10", props.getProperty("app.security.login.rate-limit.capacity"));
        assertEquals("10", props.getProperty("app.security.login.rate-limit.refill-tokens"));
        assertEquals("60", props.getProperty("app.security.login.rate-limit.refill-duration-seconds"));
    }
}
