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
    void tlsConfigurationIsCompatibleAcrossDefaultLinuxAndSimulationProfiles() {
        Properties base = load("application.yml");
        Properties linux = load("application-linux.yml");
        Properties sim = load("application-sim.yml");
        assertEquals("${ISE_VERIFY_TLS:true}", base.getProperty("app.ise.verify-tls"));
        assertNull(linux.getProperty("app.ise.verify-tls"), "linux inherits the secure default");
        assertNull(sim.getProperty("app.ise.verify-tls"), "sim inherits the secure default");
        assertTrue(linux.keySet().stream().noneMatch(k -> k.toString().startsWith("app.ise.")));
        assertTrue(sim.keySet().stream().noneMatch(k -> k.toString().startsWith("app.ise.")));
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
    void linuxProfileEnablesHealthProbesWithDbInReadiness() {
        Properties linux = load("application-linux.yml");
        assertEquals("true", linux.getProperty("management.endpoint.health.probes.enabled"));
        assertEquals("readinessState,db",
                linux.getProperty("management.endpoint.health.group.readiness.include"));
    }

    @Test
    void linuxProfileOnlyOverridesJobsAndHealthProbes() {
        Properties linux = load("application-linux.yml");
        assertTrue(linux.keySet().stream().allMatch(k ->
                        k.toString().startsWith("app.jobs.")
                                || k.toString().startsWith("management.endpoint.health.")),
                "unexpected keys in linux profile: " + linux.keySet());
    }

    @Test
    void secretsAndDbPasswordHaveNoDefaults() {
        Properties base = load("application.yml");
        assertEquals("${DB_PASSWORD}", base.getProperty("spring.datasource.password"));
        assertEquals("${JWT_SECRET}", base.getProperty("app.jwt.secret"));
        assertEquals("${SEED_ADMIN_PASSWORD}", base.getProperty("app.seed-admin.password"));
    }
}
