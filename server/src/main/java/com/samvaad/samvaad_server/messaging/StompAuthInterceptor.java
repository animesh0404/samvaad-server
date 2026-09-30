package com.samvaad.samvaad_server.messaging;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.samvaad.samvaad_server.auth.exception.InvalidAccessTokenException;
import com.samvaad.samvaad_server.auth.token.AccessTokenClaims;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.common.logging.TraceIds;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;

/**
 * Authenticates STOMP {@code CONNECT} frames with the existing Samvaad JWT
 * access token plus persisted session validation (same rules as the HTTP
 * {@code JwtAuthenticationFilter}).
 *
 * <p>Transport/auth infrastructure only: no application destinations exist in
 * this slice, so every {@code SUBSCRIBE} is denied. Per-device E2EE realtime
 * destinations will be authorized here in a later slice.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final SessionRepo sessionRepo;

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

    public StompAuthInterceptor(
            TokenService tokenService,
            SessionRepo sessionRepo) {
        this.tokenService = tokenService;
        this.sessionRepo = sessionRepo;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        // Scoped to this inbound message; cleared in afterSendCompletion below.
        // MDC is not expected to propagate into asynchronous broker delivery.
        MDC.put(TraceIds.MDC_KEY, TraceIds.resolveOrGenerate(
                firstNativeHeader(accessor, TraceIds.REQUEST_ID_HEADER),
                firstNativeHeader(accessor, TraceIds.TRACE_ID_HEADER)));
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            try {
                accessor.setUser(authenticate(accessor));
            } catch (InvalidAccessTokenException e) {
                List<String> header = accessor.getNativeHeader(AUTHORIZATION_HEADER);
                log.warn("STOMP CONNECT authentication failed authHeaderPresent={}",
                        header != null && !header.isEmpty());
                throw e;
            }
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            try {
                authorizeSubscription(accessor);
            } catch (ForbiddenOperationException e) {
                log.warn("STOMP SUBSCRIBE authorization denied destination={}",
                        accessor.getDestination());
                throw e;
            }
        }
        return message;
    }

    @Override
    public void afterSendCompletion(
            Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        MDC.remove(TraceIds.MDC_KEY);
    }

    private String firstNativeHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private UsernamePasswordAuthenticationToken authenticate(StompHeaderAccessor accessor) {
        String token = bearerToken(accessor);
        AccessTokenClaims claims;
        try {
            claims = tokenService.parseAccessToken(token);
        } catch (InvalidAccessTokenException e) {
            throw new InvalidAccessTokenException("Invalid access token");
        }
        Session session = sessionRepo.findWithUserBySessionId(claims.sessionId())
                .orElseThrow(() -> new InvalidAccessTokenException("Invalid access token"));
        if (!isSessionValid(session, claims)) {
            throw new InvalidAccessTokenException("Invalid access token");
        }
        AuthenticatedUser caller = new AuthenticatedUser(
                session.getUser().getUserId(),
                session.getUser().getRole(),
                session.getSessionId());
        return new UsernamePasswordAuthenticationToken(
                caller,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + caller.role().name())));
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

    private String bearerToken(StompHeaderAccessor accessor) {
        List<String> values = Optional.ofNullable(accessor.getNativeHeader(AUTHORIZATION_HEADER))
                .orElse(List.of());
        return values.stream()
                .filter(value -> value.startsWith(BEARER_PREFIX))
                .map(value -> value.substring(BEARER_PREFIX.length()).trim())
                .filter(token -> !token.isEmpty())
                .findFirst()
                .orElseThrow(() -> new InvalidAccessTokenException("Invalid access token"));
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        // No application destinations exist in this slice. Deny every
        // subscription; per-device E2EE realtime destinations will be
        // authorized here in a later slice.
        throw new ForbiddenOperationException();
    }
}
