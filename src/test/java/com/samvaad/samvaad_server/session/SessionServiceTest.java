package com.samvaad.samvaad_server.session;

import com.samvaad.samvaad_server.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.samvaad.samvaad_server.session.RevocationReason;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionRepo sessionRepo;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(sessionRepo);
    }

    @Test
    void countsActiveSessionsUsingSessionRepo() {
        User user = new User(UUID.randomUUID());
        given(sessionRepo.countByUserAndRevokedAtIsNullAndRefreshTokenExpiresAtAfter(eq(user), any(LocalDateTime.class)))
                .willReturn(3L);

        long count = sessionService.countActiveSessions(user);

        assertEquals(3L, count);
        then(sessionRepo).should().countByUserAndRevokedAtIsNullAndRefreshTokenExpiresAtAfter(eq(user), any(LocalDateTime.class));
    }

    @Test
    void createsAndPersistsSessionWithMetadata() {
        User user = new User(UUID.randomUUID());
        user.setUsername("animesh");

        LocalDateTime expiresAt = LocalDateTime.now().plusDays(30);

        Session savedSession = new Session();
        savedSession.setSessionId(UUID.randomUUID());
        savedSession.setUser(user);
        given(sessionRepo.save(any(Session.class))).willReturn(savedSession);

        Session result = sessionService.createSession(
                user,
                "token-hash",
                expiresAt,
                "install-123",
                ClientPlatform.WEB,
                "Samvaad Web",
                "1.0.0",
                "127.0.0.1",
                "Mozilla/5.0"
        );

        assertNotNull(result);
        assertEquals(savedSession.getSessionId(), result.getSessionId());

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        then(sessionRepo).should().save(captor.capture());
        Session created = captor.getValue();
        assertEquals(user, created.getUser());
        assertEquals("token-hash", created.getRefreshTokenHash());
        assertEquals(expiresAt, created.getRefreshTokenExpiresAt());
        assertEquals("install-123", created.getInstallationId());
        assertEquals(ClientPlatform.WEB, created.getClientPlatform());
        assertEquals("Samvaad Web", created.getClientName());
        assertEquals("1.0.0", created.getClientVersion());
        assertEquals("127.0.0.1", created.getLastSeenIp());
        assertEquals("Mozilla/5.0", created.getLastSeenUserAgent());
        assertNotNull(created.getLastAuthenticatedAt());
        assertNull(created.getRevokedAt());
    }

    @Test
    void revokesSessionWhenNotAlreadyRevoked() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setRevokedAt(null);
        given(sessionRepo.findById(sessionId)).willReturn(Optional.of(session));
        given(sessionRepo.save(any(Session.class))).willReturn(session);

        sessionService.revokeSession(sessionId, RevocationReason.USER_LOGOUT);

        assertNotNull(session.getRevokedAt());
        assertEquals(RevocationReason.USER_LOGOUT, session.getRevocationReason());
        then(sessionRepo).should().save(session);
    }

    @Test
    void idempotentWhenAlreadyRevoked() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setRevokedAt(LocalDateTime.now());
        given(sessionRepo.findById(sessionId)).willReturn(Optional.of(session));

        sessionService.revokeSession(sessionId, RevocationReason.USER_LOGOUT);

        then(sessionRepo).should(never()).save(any(Session.class));
    }

    @Test
    void noOpWhenSessionMissing() {
        UUID sessionId = UUID.randomUUID();
        given(sessionRepo.findById(sessionId)).willReturn(Optional.empty());

        sessionService.revokeSession(sessionId, RevocationReason.USER_LOGOUT);

        then(sessionRepo).should(never()).save(any(Session.class));
    }
}
