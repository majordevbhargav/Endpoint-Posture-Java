package com.endpointposture.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class PostureApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Posture-Api-Key";

    private static final String POSTURE_INGEST_PATH =
            "/api/v1/posture";
    private static final String HARDWARE_INGEST_PATH =
            "/api/v1/hardware-health";
    private static final String DIAGNOSTIC_INGEST_PATH =
            "/api/v1/diagnostics";
    private static final String SECURITY_INGEST_PATH =
            "/api/v1/security-indicators";

    private final byte[] expectedKey;

    public PostureApiKeyFilter(
            @Value("${app.posture.api-key:}") String apiKey) {
        this.expectedKey =
                apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(
            @NonNull HttpServletRequest request) {

        String uri = request.getRequestURI();

        boolean isIngestRoute =
                POSTURE_INGEST_PATH.equals(uri)
                        || HARDWARE_INGEST_PATH.equals(uri)
                        || DIAGNOSTIC_INGEST_PATH.equals(uri)
                        || SECURITY_INGEST_PATH.equals(uri);

        return !("POST".equalsIgnoreCase(request.getMethod())
                && isIngestRoute);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String presented = request.getHeader(HEADER_NAME);

        if (presented == null) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean valid =
                expectedKey.length > 0
                        && MessageDigest.isEqual(
                                expectedKey,
                                presented.getBytes(StandardCharsets.UTF_8));

        if (!valid) {
            response.setStatus(
                    HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain");
            response.getWriter().write("Invalid agent API key");
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                "posture-agent",
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_AGENT")));

        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }
}
