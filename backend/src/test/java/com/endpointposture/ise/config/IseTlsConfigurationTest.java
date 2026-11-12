package com.endpointposture.ise.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class IseTlsConfigurationTest {

    @Test
    void tlsVerificationDefaultsToEnabledAndTruststoreIsOptional() {
        IseProperties properties = new IseProperties();
        assertTrue(properties.isVerifyTls());
        assertEquals("", properties.getTrustStore());
        assertEquals("PKCS12", properties.getTrustStoreType());
    }

    @Test
    void disabledTlsIsRejectedForProductionProfiles() {
        for (String profile : new String[]{"linux", "prod", "production"}) {
            IseProperties properties = new IseProperties();
            properties.setVerifyTls(false);
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            assertThrows(IllegalStateException.class,
                    () -> new IseTlsConfigurationValidator(properties, environment));
        }
    }

    @Test
    void disabledTlsRemainsAvailableOutsideProductionProfiles() {
        IseProperties properties = new IseProperties();
        properties.setVerifyTls(false);
        MockEnvironment sim = new MockEnvironment();
        sim.setActiveProfiles("sim");
        assertDoesNotThrow(() -> new IseTlsConfigurationValidator(properties, sim));
        assertDoesNotThrow(() -> new IseTlsConfigurationValidator(properties, new MockEnvironment()));
    }
}
