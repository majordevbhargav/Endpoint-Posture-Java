package com.endpointposture.ise.config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;

/** Builds an ISE HTTP client with a configured truststore, or delegates to the JVM defaults. */
public final class IseHttpClientFactory {

    private IseHttpClientFactory() {}

    public static HttpClient create(IseProperties properties) {
        if (!properties.isVerifyTls()) {
            throw new IllegalStateException(
                    "ISE TLS certificate verification must be enabled; remove app.ise.verify-tls=false");
        }
        if (properties.getTrustStore() == null || properties.getTrustStore().isBlank()) {
            return null;
        }
        return HttpClient.newBuilder()
                .sslContext(trustStoreContext(properties))
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    private static SSLContext trustStoreContext(IseProperties properties) {
        try {
            KeyStore trustStore = KeyStore.getInstance(properties.getTrustStoreType());
            char[] password = properties.getTrustStorePassword() == null
                    ? new char[0] : properties.getTrustStorePassword().toCharArray();
            try (InputStream input = Files.newInputStream(Path.of(properties.getTrustStore()))) {
                trustStore.load(input, password.length == 0 ? null : password);
            }

            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load ISE truststore configured by app.ise.trust-store", e);
        }
    }
}
