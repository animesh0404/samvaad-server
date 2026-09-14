package com.samvaad.samvaad_server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.samvaad.samvaad_server.auth.exception.InvalidAccessTokenException;
import com.samvaad.samvaad_server.auth.token.AccessTokenClaims;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.common.logging.LogCapture;
import com.samvaad.samvaad_server.common.logging.TraceIds;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRole;

import ch.qos.logback.classic.Level;

@ExtendWith(MockitoExtension.class)
class StompAuthInterceptorLoggingTest {

    private static final String SECRET_JWT = "secret-jwt-access-token-xyz";

    @Mock
    private TokenService tokenService;

    @Mock
    private SessionRepo sessionRepo;

    @Mock
    private ConversationService conversationService;

    private StompAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthInterceptor(tokenService, sessionRepo, conversationService);
    }

    private Message<byte[]> connectMessage(String traceId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.setNativeHeader("Authorization", "Bearer " + SECRET_JWT);
        if (traceId != null) {
            accessor.setNativeHeader(TraceIds.TRACE_ID_HEADER, traceId);
        }
        return new GenericMessage<>(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void propagatesTraceIdAndClearsMdc() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User user = new User(userId);
        user.setRole(UserRole.USER);
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setUser(user);
        session.setRefreshTokenExpiresAt(LocalDateTime.now().plusDays(1));

        given(tokenService.parseAccessToken(SECRET_JWT))
                .willReturn(new AccessTokenClaims(userId, sessionId));
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));

        Message<byte[]> message = connectMessage("trace-abc");
        interceptor.preSend(message, null);

        assertEquals("trace-abc", MDC.get(TraceIds.MDC_KEY));
        interceptor.afterSendCompletion(message, null, true, null);
        assertNull(MDC.get(TraceIds.MDC_KEY));
    }

    @Test
    void logsConnectAuthFailureWithoutToken() {
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willThrow(new InvalidAccessTokenException("bad"));

        Message<byte[]> message = connectMessage(null);
        try (LogCapture logs = new LogCapture(StompAuthInterceptor.class)) {
            assertThrows(InvalidAccessTokenException.class, () -> interceptor.preSend(message, null));

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("CONNECT")));
            assertTrue(!logs.text().contains(SECRET_JWT), "access token must never be logged");
        } finally {
            interceptor.afterSendCompletion(message, null, false,
                    new InvalidAccessTokenException("bad"));
            assertNull(MDC.get(TraceIds.MDC_KEY));
        }
    }

    @Test
    void logsSubscribeDenial() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String destination = "/topic/conversations/" + conversationId;

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination(destination);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(userId, UserRole.USER, sessionId),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        Message<byte[]> message = new GenericMessage<>(new byte[0], accessor.getMessageHeaders());

        given(conversationService.isConversationParticipant(userId, conversationId)).willReturn(false);

        try (LogCapture logs = new LogCapture(StompAuthInterceptor.class)) {
            assertThrows(ForbiddenOperationException.class, () -> interceptor.preSend(message, null));

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains(destination)));
        } finally {
            interceptor.afterSendCompletion(message, null, false, new ForbiddenOperationException());
            assertNull(MDC.get(TraceIds.MDC_KEY));
        }
    }
}
