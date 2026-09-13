package com.samvaad.samvaad_server.messaging;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import com.samvaad.samvaad_server.auth.exception.InvalidAccessTokenException;
import com.samvaad.samvaad_server.auth.token.AccessTokenClaims;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;

/**
 * Authenticates STOMP {@code CONNECT} frames with the existing Samvaad JWT
 * access token plus persisted session validation (same rules as the HTTP
 * {@code JwtAuthenticationFilter}), and authorizes conversation topic
 * subscriptions to participants only.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CONVERSATION_TOPIC_PREFIX = "/topic/conversations/";

    private final TokenService tokenService;
    private final SessionRepo sessionRepo;
    private final ConversationService conversationService;

    public StompAuthInterceptor(
            TokenService tokenService,
            SessionRepo sessionRepo,
            ConversationService conversationService) {
        this.tokenService = tokenService;
        this.sessionRepo = sessionRepo;
        this.conversationService = conversationService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            accessor.setUser(authenticate(accessor));
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }
        return message;
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
        AuthenticatedUser caller = currentCaller(accessor);
        UUID conversationId = conversationTopicId(accessor.getDestination());
        if (conversationId == null
                || !conversationService.isConversationParticipant(caller.userId(), conversationId)) {
            throw new ForbiddenOperationException();
        }
    }

    private AuthenticatedUser currentCaller(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser caller) {
            return caller;
        }
        throw new ForbiddenOperationException();
    }

    private UUID conversationTopicId(String destination) {
        if (destination == null || !destination.startsWith(CONVERSATION_TOPIC_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(destination.substring(CONVERSATION_TOPIC_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
