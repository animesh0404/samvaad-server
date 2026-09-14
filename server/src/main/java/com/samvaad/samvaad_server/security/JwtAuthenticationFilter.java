package com.samvaad.samvaad_server.security;

import com.samvaad.samvaad_server.auth.exception.InvalidAccessTokenException;
import com.samvaad.samvaad_server.auth.token.AccessTokenClaims;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final SessionRepo sessionRepo;

    public JwtAuthenticationFilter(TokenService tokenService, SessionRepo sessionRepo) {
        this.tokenService = tokenService;
        this.sessionRepo = sessionRepo;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();

        try {
            AccessTokenClaims claims = tokenService.parseAccessToken(token);
            Optional<Session> session = sessionRepo.findWithUserBySessionId(claims.sessionId());

            if (session.isPresent() && isSessionValid(session.get(), claims)) {
                authenticate(claims, session.get());
            } else if (session.isPresent() && isLogoutRequest(request)) {
                authenticate(claims, session.get());
            } else if (session.isPresent()) {
                log.debug("HTTP authentication skipped: invalid session");
            } else {
                log.debug("HTTP authentication skipped: unknown session");
            }
        } catch (InvalidAccessTokenException e) {
            log.debug("HTTP authentication skipped: invalid access token");
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private boolean isLogoutRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && "/api/auth/logout".equals(request.getRequestURI());
    }

    private boolean isSessionValid(Session session, AccessTokenClaims claims) {
        if (session.getRevokedAt() != null) {
            return false;
        }
        if (!session.getRefreshTokenExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }
        return session.getUser().getUserId().equals(claims.userId());
    }

    private void authenticate(AccessTokenClaims claims, Session session) {
        AuthenticatedUser principal = new AuthenticatedUser(
                session.getUser().getUserId(),
                session.getUser().getRole(),
                session.getSessionId()
        );
        SimpleGrantedAuthority authority =
                new SimpleGrantedAuthority("ROLE_" + session.getUser().getRole().name());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, List.of(authority));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}