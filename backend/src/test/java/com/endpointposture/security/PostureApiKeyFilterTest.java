package com.endpointposture.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PostureApiKeyFilter is the only path a PowerShell agent authenticates
 * through - no user login, just a shared secret scoped to exactly two
 * routes. These tests pin down its documented behavior: no header steps
 * aside, a valid key grants ROLE_AGENT only, a wrong or blank-configured
 * key fails closed with a plain 401 (not a Spring /error redispatch), and
 * every other route is never touched by this filter at all.
 */
class PostureApiKeyFilterTest {

    private static final String VALID_KEY = "correct-horse-battery-staple";

    private PostureApiKeyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PostureApiKeyFilter(VALID_KEY);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- shouldNotFilter routing ---

    @Test
    void postToPostureIngestRouteIsFiltered() {
        assertFalse(filter.shouldNotFilter(postRequest("/api/v1/posture")));
    }

    @Test
    void postToHardwareIngestRouteIsFiltered() {
        assertFalse(filter.shouldNotFilter(postRequest("/api/v1/hardware-health")));
    }

    @Test
    void getToIngestRouteIsSkipped_onlyPostIsGuarded() {
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/posture")));
    }

    @Test
    void postToAnyOtherRouteIsSkipped() {
        assertTrue(filter.shouldNotFilter(postRequest("/api/v1/endpoints")));
    }

    // --- doFilterInternal behavior ---

    @Test
    void noHeaderPresentStepsAsideAndContinuesUnauthenticated() throws Exception {
        MockHttpServletRequest req = postRequest("/api/v1/posture");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, res.getStatus()); // untouched
    }

    @Test
    void validKeyAuthenticatesAsAgentAndContinues() throws Exception {
        MockHttpServletRequest req = postRequest("/api/v1/posture");
        req.addHeader(PostureApiKeyFilter.HEADER_NAME, VALID_KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("posture-agent", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_AGENT")));
    }

    @Test
    void wrongKeyIsRejectedWith401AndChainNeverRuns() throws Exception {
        MockHttpServletRequest req = postRequest("/api/v1/posture");
        req.addHeader(PostureApiKeyFilter.HEADER_NAME, "definitely-not-it");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(401, res.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void emptyConfiguredKeyRejectsEveryPresentedKey_failClosed() throws Exception {
        PostureApiKeyFilter blankFilter = new PostureApiKeyFilter("");
        MockHttpServletRequest req = postRequest("/api/v1/posture");
        req.addHeader(PostureApiKeyFilter.HEADER_NAME, "");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        blankFilter.doFilterInternal(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(401, res.getStatus());
    }

    @Test
    void hardwareRouteAcceptsTheSameKey() throws Exception {
        MockHttpServletRequest req = postRequest("/api/v1/hardware-health");
        req.addHeader(PostureApiKeyFilter.HEADER_NAME, VALID_KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private MockHttpServletRequest postRequest(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }
}