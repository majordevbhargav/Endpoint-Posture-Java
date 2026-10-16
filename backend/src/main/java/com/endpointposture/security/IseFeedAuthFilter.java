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
import java.util.Base64;
import java.util.List;

/**
 * HTTP Basic authentication for the ISE pxGrid Direct feed, which can only send Basic auth.
 *
 * <p>Runs ONLY on {@code GET /api/v1/integrations/ise/endpoints}; on every other route
 * the credential does nothing, so it can never reach the operator API. A valid pair
 * grants {@code ROLE_ISE_FEED} for that one request. No Basic header means the request
 * continues unauthenticated (an ADMIN JWT may still pass); a wrong one is a {@code 401}.
 * Fails closed: with no password configured every Basic header is refused.</p>
 */
@Component
public class IseFeedAuthFilter extends OncePerRequestFilter {

    public static final String FEED_PATH = "/api/v1/integrations/ise/endpoints";

    private final byte[] expectedUser;
    private final byte[] expectedPassword;

    public IseFeedAuthFilter(@Value("${app.ise-feed.username:ise-feed}") String username,
                             @Value("${app.ise-feed.password:}") String password) {
        this.expectedUser = username.getBytes(StandardCharsets.UTF_8);
        this.expectedPassword = password.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !("GET".equalsIgnoreCase(request.getMethod()) && FEED_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            chain.doFilter(request, response);
            return;
        }

        String[] pair = decode(header.substring(6).trim());
        boolean valid = pair != null
                && expectedPassword.length > 0
                // both comparisons always run, so timing does not reveal which part was wrong
                & MessageDigest.isEqual(expectedUser, pair[0].getBytes(StandardCharsets.UTF_8))
                & MessageDigest.isEqual(expectedPassword, pair[1].getBytes(StandardCharsets.UTF_8));

        if (!valid) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Basic realm=\"ise-feed\"");
            response.setContentType("text/plain");
            response.getWriter().write("Invalid ISE feed credentials");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "ise-feed", null, List.of(new SimpleGrantedAuthority("ROLE_ISE_FEED"))));
        chain.doFilter(request, response);
    }

    /** @return {user, password}, or {@code null} if the value is not valid Base64 "user:password" */
    private static String[] decode(String b64) {
        try {
            String text = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            int colon = text.indexOf(':');
            return colon < 0 ? null : new String[] { text.substring(0, colon), text.substring(colon + 1) };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}