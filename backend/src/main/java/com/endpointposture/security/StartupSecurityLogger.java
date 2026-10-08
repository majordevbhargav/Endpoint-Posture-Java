package com.endpointposture.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Logs security configuration warnings at application startup:
 * - WARN when app.ise.verify-tls is false (insecure TLS verification).
 * - WARN when app.docs.public is true (Swagger UI / OpenAPI specs publicly exposed without auth).
 */
@Component
public class StartupSecurityLogger implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityLogger.class);

    private final boolean iseVerifyTls;
    private final boolean docsPublic;

    public StartupSecurityLogger(
            @Value("${app.ise.verify-tls:true}") boolean iseVerifyTls,
            @Value("${app.docs.public:false}") boolean docsPublic) {
        this.iseVerifyTls = iseVerifyTls;
        this.docsPublic = docsPublic;
    }

    @Override
    public void run(String... args) {
        if (!iseVerifyTls) {
            log.warn("SECURITY WARNING: app.ise.verify-tls is set to FALSE. ISE TLS certificate verification is disabled! "
                    + "This must never be used in production.");
        }
        if (docsPublic) {
            log.warn("SECURITY WARNING: app.docs.public is set to TRUE. Swagger UI and /v3/api-docs endpoints are publicly accessible without authentication. "
                    + "Set app.docs.public to false in production.");
        }
    }
}
