package com.samvaad.samvaad_server.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.common.logging.LogCapture;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;

import ch.qos.logback.classic.Level;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceLoggingTest {

    private static final String PRESENTED_TOKEN = "presented-raw-token-secret-xyz";
    private static final String PRESENTED_HASH = "presented-hash-xyz";
    private static final String NEXT_TOKEN = "next-raw-token-secret-xyz";

    @Mock
    private SessionRepo sessionRepo;

    @Mock
    private TokenService tokenService;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(sessionRepo, tokenService);
    }

    private Session activeSession(UUID userId, UUID sessionId) {
        User user = new User(userId);
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setUser(user);
        session.setRefreshTokenHash(PRESENTED_HASH);
        session.setRefreshTokenExpiresAt(LocalDateTime.now().plusDays(1));
        return session;
    }

    @Test
    void logsSuccessfulRefreshWithoutTokenMaterial() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Session session = activeSession(userId, sessionId);

        given(tokenService.hashRefreshToken(PRESENTED_TOKEN)).willReturn(PRESENTED_HASH);
        given(sessionRepo.findByRefreshTokenHash(PRESENTED_HASH)).willReturn(Optional.of(session));
        given(tokenService.generateRefreshToken()).willReturn(NEXT_TOKEN);
        given(tokenService.hashRefreshToken(NEXT_TOKEN)).willReturn("next-hash");
        given(tokenService.getRefreshTokenValidityDays()).willReturn(30L);
        given(tokenService.generateAccessToken(session.getUser(), sessionId)).willReturn("access-token");

        try (LogCapture logs = new LogCapture(RefreshTokenService.class)) {
            refreshTokenService.refresh(PRESENTED_TOKEN);

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains(sessionId.toString())));
            String text = logs.text();
            assertTrue(!text.contains(PRESENTED_TOKEN), "refresh token must never be logged");
            assertTrue(!text.contains(NEXT_TOKEN), "refresh token must never be logged");
            assertTrue(!text.contains(PRESENTED_HASH), "refresh-token hash must never be logged");
        }
    }

    @Test
    void logsUnknownTokenAtWarnWithoutTokenMaterial() {
        given(tokenService.hashRefreshToken(PRESENTED_TOKEN)).willReturn(PRESENTED_HASH);
        given(sessionRepo.findByRefreshTokenHash(PRESENTED_HASH)).willReturn(Optional.empty());

        try (LogCapture logs = new LogCapture(RefreshTokenService.class)) {
            assertThrows(InvalidRefreshTokenException.class,
                    () -> refreshTokenService.refresh(PRESENTED_TOKEN));

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.WARN));
            String text = logs.text();
            assertTrue(!text.contains(PRESENTED_TOKEN), "refresh token must never be logged");
            assertTrue(!text.contains(PRESENTED_HASH), "refresh-token hash must never be logged");
        }
    }

    @Test
    void logsRevokedSessionAtWarn() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Session session = activeSession(userId, sessionId);
        session.setRevokedAt(LocalDateTime.now());

        given(tokenService.hashRefreshToken(PRESENTED_TOKEN)).willReturn(PRESENTED_HASH);
        given(sessionRepo.findByRefreshTokenHash(PRESENTED_HASH)).willReturn(Optional.of(session));

        try (LogCapture logs = new LogCapture(RefreshTokenService.class)) {
            assertThrows(InvalidRefreshTokenException.class,
                    () -> refreshTokenService.refresh(PRESENTED_TOKEN));

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains(sessionId.toString())));
            assertTrue(!logs.text().contains(PRESENTED_TOKEN), "refresh token must never be logged");
        }
    }
}
