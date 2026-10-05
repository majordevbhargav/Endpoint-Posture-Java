package com.endpointposture.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostureApiKeyFilterTest {

    private static final String VALID_KEY = "correct-horse-battery-staple";
    private static final String[] INGEST_ROUTES = {
            "/api/v1/posture", "/api/v1/hardware-health",
            "/api/v1/diagnostics", "/api/v1/security-indicators"
    };

    private PostureApiKeyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PostureApiKeyFilter(VALID_KEY);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private MockHttpServletRequest post(String uri) { return new MockHttpServletRequest("POST", uri); }

    // ---- routing ----
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/posture", "/api/v1/hardware-health",
            "/api/v1/diagnostics", "/api/v1/security-indicators"})
    void postToEveryIngestRouteIsFiltered(String uri) {
        assertFalse(filter.shouldNotFilter(post(uri)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/posture", "/api/v1/hardware-health",
            "/api/v1/diagnostics", "/api/v1/security-indicators"})
    void getToAnIngestRouteIsSkipped(String uri) {
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", uri)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/endpoints", "/api/v1/jobs", "/api/v1/ise/posture/share",
            "/api/v1/endpoints/123/diagnostics", "/api/v1/policy/apps"})
    void postToAnyOtherRouteIsSkipped(String uri) {
        assertTrue(filter.shouldNotFilter(post(uri)));
    }

    // ---- behaviour, per route ----
    @Test
    void noHeaderStepsAsideOnEveryRoute() throws Exception {
        for (String uri : INGEST_ROUTES) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest req = post(uri);
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(req, res, chain);

            verify(chain).doFilter(req, res);
            assertNull(SecurityContextHolder.getContext().getAuthentication(), uri);
            assertEquals(200, res.getStatus(), uri);
        }
    }

    @Test
    void validKeyGrantsRoleAgentOnEveryRoute() throws Exception {
        for (String uri : INGEST_ROUTES) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest req = post(uri);
            req.addHeader(PostureApiKeyFilter.HEADER_NAME, VALID_KEY);
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(req, new MockHttpServletResponse(), chain);

            verify(chain).doFilter(any(), any());
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth, uri);
            assertEquals("posture-agent", auth.getPrincipal());
            assertEquals(1, auth.getAuthorities().size(), uri);
            assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_AGENT")));
        }
    }

    @Test
    void wrongKeyGets401AndChainNeverRunsOnEveryRoute() throws Exception {
        for (String uri : INGEST_ROUTES) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest req = post(uri);
            req.addHeader(PostureApiKeyFilter.HEADER_NAME, "definitely-not-it");
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(req, res, chain);

            verify(chain, never()).doFilter(any(), any());
            assertEquals(401, res.getStatus(), uri);
            assertNull(SecurityContextHolder.getContext().getAuthentication(), uri);
        }
    }

    @Test
    void keyWithTrailingWhitespaceIsNotAccepted() throws Exception {
        MockHttpServletRequest req = post("/api/v1/diagnostics");
        req.addHeader(PostureApiKeyFilter.HEADER_NAME, VALID_KEY + " ");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, mock(FilterChain.class));
        assertEquals(401, res.getStatus());
    }

    @Test
    void emptyConfiguredKeyRejectsEveryPresentedKeyFailClosed() throws Exception {
        PostureApiKeyFilter blank = new PostureApiKeyFilter("");
        for (String presented : new String[]{"", "anything"}) {
            MockHttpServletRequest req = post("/api/v1/security-indicators");
            req.addHeader(PostureApiKeyFilter.HEADER_NAME, presented);
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            blank.doFilterInternal(req, res, chain);

            verify(chain, never()).doFilter(any(), any());
            assertEquals(401, res.getStatus());
        }
    }

    @Test
    void bodyOfA401SaysInvalidKeyWithoutRevealingTheExpectedOne() throws Exception {
        MockHttpServletRequest req = post("/api/v1/posture");
        req.addHeader(PostureApiKeyFilter.HEADER_NAME, "wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, mock(FilterChain.class));
        assertEquals("Invalid agent API key", res.getContentAsString());
        assertFalse(res.getContentAsString().contains(VALID_KEY));
    }
}