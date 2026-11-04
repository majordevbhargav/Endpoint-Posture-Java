package com.endpointposture.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** PR2: the linux profile disables local agent execution; default and sim stay untouched. */
class LinuxProfileConfigTest {

    private static Properties load(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        Properties p = yaml.getObject();
        assertNotNull(p, file + " should load");
        return p;
    }

    @Test
    void linuxProfileDisablesWorkersAndRecheck() {
        Properties linux = load("application-linux.yml");
        assertEquals("false", linux.getProperty("app.jobs.workers.enabled"));
        assertEquals("false", linux.getProperty("app.jobs.recheck.enabled"));
    }

    @Test
    void defaultProfileStillEnablesWorkersAndRecheck() {
        Properties base = load("application.yml");
        assertEquals("true", base.getProperty("app.jobs.workers.enabled"));
        assertEquals("true", base.getProperty("app.jobs.recheck.enabled"));
    }

    @Test
    void agentCallbackUrlsAreEnvDrivenWithLocalDefaults() {
        Properties base = load("application.yml");
        assertEquals("${POSTURE_SERVER_URL:http://localhost:8090/api/v1/posture}",
                base.getProperty("app.posture.server-url"));
        for (String k : new String[]{"hardware", "diagnostics", "security-indicators"}) {
            assertEquals("${APP_SERVER_BASE_URL:http://localhost:8090}",
                    base.getProperty("app." + k + ".server-base-url"), k);
        }
    }

    @Test
    void linuxProfileDoesNotTouchSimOrUnrelatedKeys() {
        Properties linux = load("application-linux.yml");
        assertTrue(linux.keySet().stream().allMatch(k -> k.toString().startsWith("app.jobs.")),
                "linux profile should only override app.jobs.*: " + linux.keySet());
    }
}
