package com.endpointposture.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IseFeedAuthFilterTest {

    private IseFeedAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IseFeedAuthFilter("ise-feed", "s3cret:with:colons");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private MockHttpServletRequest feed(String authorization) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", IseFeedAuthFilter.FEED_PATH);
        if (authorization != null) req.addHeader("Authorization", authorization);
        return req;
    }

    private String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void onlyTheFeedGetRouteIsFiltered() {
        assertFalse(filter.shouldNotFilter(feed(null)));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("POST", IseFeedAuthFilter.FEED_PATH)));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/endpoints")));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/integrations/ise/sharing-rule")));
    }

    @Test
    void validCredentialsGrantOnlyRoleIseFeed() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(feed(basic("ise-feed", "s3cret:with:colons")), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(1, auth.getAuthorities().size());
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ISE_FEED")));
    }

    @Test
    void wrongPasswordWrongUserAndGarbageGet401() throws Exception {
        for (String h : new String[] { basic("ise-feed", "nope"), basic("other", "s3cret:with:colons"),
                "Basic !!!not-base64!!!", "Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes()) }) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilterInternal(feed(h), res, chain);
            assertEquals(401, res.getStatus(), h);
            verify(chain, never()).doFilter(any(), any());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @Test
    void noBasicHeaderLeavesTheRequestToOtherAuthentication() throws Exception {
        for (String h : new String[] { null, "Bearer abc" }) {
            FilterChain chain = mock(FilterChain.class);
            filter.doFilterInternal(feed(h), new MockHttpServletResponse(), chain);
            verify(chain).doFilter(any(), any());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @Test
    void anUnconfiguredPasswordRefusesEveryBasicHeaderFailClosed() throws Exception {
        IseFeedAuthFilter blank = new IseFeedAuthFilter("ise-feed", "");
        MockHttpServletResponse res = new MockHttpServletResponse();
        blank.doFilterInternal(feed(basic("ise-feed", "")), res, mock(FilterChain.class));
        assertEquals(401, res.getStatus());
    }

    @Test
    void the401BodyNeverRevealsTheExpectedPassword() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(feed(basic("ise-feed", "bad")), res, mock(FilterChain.class));
        assertFalse(res.getContentAsString().contains("s3cret"));
    }
}