package com.endpointposture.ise.config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/** Builds the optional ISE HTTP client with either a private CA truststore or the
 * deliberately insecure, non-production compatibility mode. A {@code null}
 * result means the JDK's normal default trust configuration should be used. */
public final class IseHttpClientFactory {

    private IseHttpClientFactory() {}

    public static HttpClient create(IseProperties properties) {
        if (!properties.isVerifyTls()) {
            return insecureClient();
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

    private static HttpClient insecureClient() {
        try {
            TrustManager[] trustAll = { new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            } };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(15)).build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build an insecure HTTP client for app.ise.verify-tls=false", e);
        }
    }
}
