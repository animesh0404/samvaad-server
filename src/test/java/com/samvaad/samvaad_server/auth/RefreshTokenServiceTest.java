package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final String PRESENTED_TOKEN = "presented-refresh-token";
    private static final String PRESENTED_HASH = "presented-hash";
    private static final String ROTATED_TOKEN = "rotated-refresh-token";
    private static final String ROTATED_HASH = "rotated-hash";

    @Mock
    private SessionRepo sessionRepo;

    @Mock
    private TokenService tokenService;

    private RefreshTokenService refreshTokenService;

    private User user;
    private Session session;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(sessionRepo, tokenService);

        user = new User(UUID.randomUUID());
        user.setUsername("animesh");

        session = new Session();
        session.setSessionId(UUID.randomUUID());
        session.setUser(user);
        session.setRefreshTokenHash(PRESENTED_HASH);
        session.setRefreshTokenExpiresAt(LocalDateTime.now().plusDays(30));
        session.setInstallationId("device-123");
        session.setClientPlatform(ClientPlatform.WEB);
        session.setLastAuthenticatedAt(LocalDateTime.now());
    }

    @Test
    void rotatesRefreshTokenAndReturnsNewAccessToken() {
        given(tokenService.hashRefreshToken(PRESENTED_TOKEN)).willReturn(PRESENTED_HASH);
        given(sessionRepo.findByRefreshTokenHash(PRESENTED_HASH)).willReturn(Optional.of(session));
        given(tokenService.generateRefreshToken()).willReturn(ROTATED_TOKEN);
        given(tokenService.hashRefreshToken(ROTATED_TOKEN)).willReturn(ROTATED_HASH);
        given(tokenService.getRefreshTokenValidityDays()).willReturn(30L);
        given(tokenService.generateAccessToken(user, session.getSessionId())).willReturn("new-access-token");
        given(tokenService.getAccessTokenValiditySeconds()).willReturn(86400L);

        LoginResponseDto response = refreshTokenService.refresh(PRESENTED_TOKEN);

        assertEquals("new-access-token", response.accessToken());
        assertEquals(ROTATED_TOKEN, response.refreshToken());
        assertEquals(86400L, response.expiresIn());
        assertEquals(session.getSessionId(), response.sessionId());

        assertEquals(ROTATED_HASH, session.getRefreshTokenHash());
        assertNotEquals(PRESENTED_HASH, session.getRefreshTokenHash());
        verify(sessionRepo).save(session);
    }

    @Test
    void rejectsUnknownRefreshToken() {
        given(tokenService.hashRefreshToken(PRESENTED_TOKEN)).willReturn(PRESENTED_HASH);
        given(sessionRepo.findByRefreshTokenHash(PRESENTED_HASH)).willReturn(Optional.empty());

        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh(PRESENTED_TOKEN));

        verify(sessionRepo, never()).save(any());
    }

    @Test
    void rejectsAlreadyConsumedRefreshToken() {
        session.setRefreshTokenHash(ROTATED_HASH);

        given(tokenService.hashRefreshToken(PRESENTED_TOKEN)).willReturn(PRESENTED_HASH);
        given(sessionRepo.findByRefreshTokenHash(PRESENTED_HASH)).willReturn(Optional.empty());

        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh(PRESENTED_TOKEN));

        verify(sessionRepo, never()).save(any());
    }

    @Test
    void rejectsRevokedSession() {
        session.setRevokedAt(LocalDateTime.now());

        given(tokenService.hashRefreshToken(PRESENTED_TOKEN)).willReturn(PRESENTED_HASH);
        given(sessionRepo.findByRefreshTokenHash(PRESENTED_HASH)).willReturn(Optional.of(session));

        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh(PRESENTED_TOKEN));

        verify(sessionRepo, never()).save(any());
    }

    @Test
    void rejectsExpiredRefreshToken() {
        session.setRefreshTokenExpiresAt(LocalDateTime.now().minusSeconds(1));

        given(tokenService.hashRefreshToken(PRESENTED_TOKEN)).willReturn(PRESENTED_HASH);
        given(sessionRepo.findByRefreshTokenHash(PRESENTED_HASH)).willReturn(Optional.of(session));

        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh(PRESENTED_TOKEN));

        verify(sessionRepo, never()).save(any());
    }
}