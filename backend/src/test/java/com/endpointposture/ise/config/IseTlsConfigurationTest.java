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
        assertDoesNotThrow(() -> new IseTlsConfigurationValidator(properties, new MockEnvironment()));
    }

    @Test
    void disabledTlsIsRejectedWithoutAProfileAndForEveryDeploymentProfile() {
        for (String[] profiles : new String[][] { {}, {"default"}, {"linux"}, {"prod"}, {"production"}, {"sim"}, {"dev"} }) {
            IseProperties properties = new IseProperties();
            properties.setVerifyTls(false);
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);
            assertThrows(IllegalStateException.class,
                    () -> new IseTlsConfigurationValidator(properties, environment),
                    () -> "profiles=" + String.join(",", profiles));
        }
    }

    @Test
    void enabledTlsIsAcceptedForJvmsDefaultTrustConfiguration() {
        IseProperties properties = new IseProperties();
        properties.setVerifyTls(true);
        assertNull(IseHttpClientFactory.create(properties),
                "No custom client means the JDK default SSL trust configuration is used");
        assertDoesNotThrow(() -> new IseTlsConfigurationValidator(properties, new MockEnvironment()));
    }

    @Test
    void enabledTlsIsAcceptedWithACustomTruststore() {
        IseProperties properties = new IseProperties();
        properties.setVerifyTls(true);
        properties.setTrustStore("test-truststore.p12");
        assertDoesNotThrow(() -> new IseTlsConfigurationValidator(properties, new MockEnvironment()));
    }

    @Test
    void clientFactoryAlsoRejectsDisabledTls() {
        IseProperties properties = new IseProperties();
        properties.setVerifyTls(false);
        assertThrows(IllegalStateException.class, () -> IseHttpClientFactory.create(properties));
    }
}
