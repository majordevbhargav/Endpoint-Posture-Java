package com.endpointposture.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JwtAuthFilter never rejects a request itself - it only decides whether to
 * populate the SecurityContext, and always calls the chain either way,
 * leaving SecurityConfig's authorizeHttpRequests rules to do the actual
 * rejecting. The token proves identity; role and enabled come from the
 * database, so a demotion or disable takes effect on the very next request.
 */
class JwtAuthFilterTest {

    private static final String SECRET = "test-only-secret-key-at-least-256-bits-long-for-hmac-sha";

    private JwtService jwtService;
    private UserRepository userRepository;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 60);
        userRepository = mock(UserRepository.class);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(
                User.builder().username("alice").role(Role.ADMIN).enabled(true).build()));
        filter = new JwtAuthFilter(jwtService, userRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    @Test
    void noAuthorizationHeaderLeavesRequestUnauthenticated() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void headerWithoutBearerPrefixIsIgnored() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void validTokenAuthenticatesWithUsernameAndRolePrefixedAuthority() throws Exception {
        MockHttpServletRequest req = bearer(jwtService.generateToken("alice", "ADMIN"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("alice", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void disabledUserWithAValidTokenIsUnauthenticated() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(
                User.builder().username("alice").role(Role.ADMIN).enabled(false).build()));
        MockHttpServletRequest req = bearer(jwtService.generateToken("alice", "ADMIN"));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void deletedUserWithAValidTokenIsUnauthenticated() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        MockHttpServletRequest req = bearer(jwtService.generateToken("alice", "ADMIN"));

        filter.doFilterInternal(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void roleInTheDatabaseWinsOverTheRoleInTheToken() throws Exception {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(
                User.builder().username("alice").role(Role.VIEWER).enabled(true).build()));
        MockHttpServletRequest req = bearer(jwtService.generateToken("alice", "ADMIN")); // stale claim

        filter.doFilterInternal(req, new MockHttpServletResponse(), mock(FilterChain.class));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_VIEWER")));
        assertTrue(auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void malformedTokenLeavesRequestUnauthenticatedButStillContinuesTheChain() throws Exception {
        MockHttpServletRequest req = bearer("not-a-real-jwt");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() throws Exception {
        JwtService otherService = new JwtService("a-completely-different-256-bit-secret-value-here", 60);
        MockHttpServletRequest req = bearer(otherService.generateToken("mallory", "ADMIN"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        JwtService expiredService = new JwtService(SECRET, -1);
        MockHttpServletRequest req = bearer(expiredService.generateToken("bob", "ADMIN"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}