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

/**
 * Lets the PowerShell posture and hardware-health agents submit results
 * without a user login.
 *
 * <p>Both agents run as child processes of the backend and cannot easily
 * hold a JWT, so they authenticate with a shared secret sent in the
 * {@value #HEADER_NAME} header. The secret is configured as
 * {@code app.posture.api-key} (override with the {@code POSTURE_API_KEY}
 * environment variable) and is handed to each agent through its process
 * environment, not its command line.</p>
 *
 * <p>Scope is deliberately narrow: this filter only looks at the two
 * ingestion routes below, and a valid key grants only the
 * {@code ROLE_AGENT} authority — enough to submit a report, nothing
 * else. Behaviour by case:</p>
 * <ul>
 *   <li>No key header: the filter steps aside (a normal admin JWT still works).</li>
 *   <li>Valid key: request is authenticated as {@code posture-agent}.</li>
 *   <li>Wrong key, or no key configured on the server: {@code 401}. An empty
 *       configured key never matches anything (fail closed).</li>
 * </ul>
 *
 * <p>Originally scoped to posture ingestion only; extended to also cover
 * hardware-health ingestion once that module was built, rather than
 * duplicating this filter for a second route with identical logic.</p>
 */
@Component
public class PostureApiKeyFilter extends OncePerRequestFilter {

    /** Name of the HTTP header carrying the agent's API key. */
    public static final String HEADER_NAME = "X-Posture-Api-Key";

    private static final String POSTURE_INGEST_PATH = "/api/v1/posture";
    private static final String HARDWARE_INGEST_PATH = "/api/v1/hardware-health";

    private final byte[] expectedKey;

    /**
     * @param apiKey the configured shared secret; may be empty, in which case
     *               every presented key is rejected
     */
    public PostureApiKeyFilter(@Value("${app.posture.api-key:}") String apiKey) {
        this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        boolean isIngestRoute = POSTURE_INGEST_PATH.equals(request.getRequestURI())
                || HARDWARE_INGEST_PATH.equals(request.getRequestURI());
        return !("POST".equalsIgnoreCase(request.getMethod()) && isIngestRoute);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String presented = request.getHeader(HEADER_NAME);

        if (presented == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // MessageDigest.isEqual compares in constant time, so response timing
        // does not leak how many leading characters of the key were correct.
        boolean valid = expectedKey.length > 0
                && MessageDigest.isEqual(expectedKey, presented.getBytes(StandardCharsets.UTF_8));

        if (!valid) {
            // Written directly instead of response.sendError(...): sendError
            // triggers an /error dispatch, which Spring Security would then
            // reject again and turn into a confusing 403.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain");
            response.getWriter().write("Invalid agent API key");
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                "posture-agent", null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }
}