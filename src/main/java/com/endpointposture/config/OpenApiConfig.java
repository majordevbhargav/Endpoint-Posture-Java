package com.endpointposture.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * Global OpenAPI/Swagger configuration.
 *
 * Adds a JWT "Authorize" button to Swagger UI: paste the token returned by
 * {@code POST /api/v1/auth/login} once, and it is automatically attached
 * as a Bearer Authorization header on every subsequent "Try it out" call
 * in the UI - without this, every protected endpoint (everything except
 * /api/v1/auth/**) is untestable from Swagger UI itself.
 */
@OpenAPIDefinition(
        info = @Info(
                title = "VE Compliance Engine API",
                version = "0.1.0",
                description = "Endpoint posture and compliance visibility platform. "
                        + "Observes endpoint posture as fact; Cisco ISE remains the "
                        + "sole network-enforcement authority. See the project "
                        + "documentation for the full observation/enforcement model."
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {
}