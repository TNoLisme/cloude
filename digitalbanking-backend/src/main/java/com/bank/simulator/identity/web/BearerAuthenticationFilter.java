package com.bank.simulator.identity.web;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.security.JwtAccessTokenCodec;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class BearerAuthenticationFilter extends OncePerRequestFilter {

    private final JwtAccessTokenCodec codec;

    public BearerAuthenticationFilter(JwtAccessTokenCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = codec.verify(header.substring(7));
                UUID userId = UUID.fromString(claims.getSubject());
                List<String> roles = claims.get("roles", List.class);
                Set<String> roleSet = roles == null ? Set.of() : Set.copyOf(roles);
                AuthenticatedActor actor = new AuthenticatedActor(userId, roleSet);
                Collection<SimpleGrantedAuthority> authorities = roleSet.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(actor, null, authorities));
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
