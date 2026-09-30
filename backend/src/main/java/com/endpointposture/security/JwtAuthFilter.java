package com.endpointposture.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates human/API callers from an {@code Authorization: Bearer <jwt>}
 * header.
 *
 * <p>
 * If the header carries a valid token, the request is marked as
 * authenticated with the token's user and role. If the header is missing or
 * the token is invalid, the filter does nothing and lets the request
 * continue unauthenticated - the rules in {@link SecurityConfig} then reject
 * it for any protected route.
 * </p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtService.isValid(token)) {
                // The token proves who they are. Role and enabled come from the DB,
                // so a demotion or disable takes effect immediately, not in 8 hours.
                userRepository.findByUsername(jwtService.extractUsername(token))
                        .filter(User::isEnabled)
                        .ifPresent(u -> {
                            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()));
                            SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken(u.getUsername(), null, authorities));
                        });
            }
        }
        filterChain.doFilter(request, response);
    }
}
