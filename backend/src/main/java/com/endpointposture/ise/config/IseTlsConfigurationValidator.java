package com.endpointposture.ise.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Rejects disabled ISE certificate verification in production deployments. */
@Component
public class IseTlsConfigurationValidator {

    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "linux");

    public IseTlsConfigurationValidator(IseProperties properties, Environment environment) {
        validate(properties, environment.getActiveProfiles());
    }

    static void validate(IseProperties properties, String... activeProfiles) {
        boolean production = Arrays.stream(activeProfiles)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(PRODUCTION_PROFILES::contains);
        if (production && !properties.isVerifyTls()) {
            throw new IllegalStateException(
                    "ISE TLS certificate verification must be enabled in production profiles; "
                            + "remove app.ise.verify-tls=false");
        }
    }
}
