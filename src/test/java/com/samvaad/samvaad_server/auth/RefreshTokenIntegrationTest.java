package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefreshTokenIntegrationTest {

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String rawRefreshToken;
    private UUID initialSessionId;

    @BeforeEach
    void setUp() {
        sessionRepo.deleteAll();
        userRepo.deleteAll();

        User user = new User();
        user.setUsername("refresh_integration_user");
        user.setEmail("refresh-integration@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        userRepo.save(user);

        LoginResponseDto loginResponse = authenticationService.login(
                new LoginRequestDto(
                        "refresh_integration_user",
                        "secret123",
                        "inst-refresh-integration",
                        ClientPlatform.WEB,
                        "Samvaad Web",
                        "1.0.0"
                ),
                "192.168.1.10",
                "UserAgent"
        );

        rawRefreshToken = loginResponse.refreshToken();
        initialSessionId = loginResponse.sessionId();
    }

    @Test
    void rotatesTokenInPlaceAndExtendsExpiryByThirtyDays() {
        Session before = sessionRepo.findById(initialSessionId).orElseThrow();
        LocalDateTime beforeExpiry = before.getRefreshTokenExpiresAt();

        LoginResponseDto rotated = refreshTokenService.refresh(rawRefreshToken);

        assertNotNull(rotated.accessToken());
        assertNotEquals(rawRefreshToken, rotated.refreshToken());
        assertEquals(86400L, rotated.expiresIn());
        assertEquals(initialSessionId, rotated.sessionId());

        Session after = sessionRepo.findById(initialSessionId).orElseThrow();
        assertEquals(tokenService.hashRefreshToken(rotated.refreshToken()), after.getRefreshTokenHash());
        assertNotEquals(tokenService.hashRefreshToken(rawRefreshToken), after.getRefreshTokenHash());
        assertTrue(after.getRefreshTokenExpiresAt().isAfter(beforeExpiry));
        assertTrue(after.getRefreshTokenExpiresAt().isAfter(LocalDateTime.now().plusDays(29)));
    }

    @Test
    void rejectsReusedOldTokenButKeepsSessionAndR2Valid() {
        LoginResponseDto rotated = refreshTokenService.refresh(rawRefreshToken);

        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh(rawRefreshToken));

        LoginResponseDto second = refreshTokenService.refresh(rotated.refreshToken());
        assertEquals(initialSessionId, second.sessionId());
        assertEquals(1L, sessionRepo.count());
    }

    @Test
    void rejectsUnknownRefreshToken() {
        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh("definitely-not-a-real-refresh-token"));
    }

    @Test
    void rejectsExpiredRefreshToken() {
        Session session = sessionRepo.findById(initialSessionId).orElseThrow();
        session.setRefreshTokenExpiresAt(LocalDateTime.now().minusSeconds(1));
        sessionRepo.saveAndFlush(session);

        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh(rawRefreshToken));
    }

    @Test
    void rejectsRevokedSession() {
        Session session = sessionRepo.findById(initialSessionId).orElseThrow();
        session.setRevokedAt(LocalDateTime.now());
        sessionRepo.saveAndFlush(session);

        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh(rawRefreshToken));
    }

    @Test
    void doesNotUpdateLastAuthenticatedAtOnRefresh() {
        LocalDateTime before = sessionRepo.findById(initialSessionId).orElseThrow().getLastAuthenticatedAt();

        refreshTokenService.refresh(rawRefreshToken);

        LocalDateTime after = sessionRepo.findById(initialSessionId).orElseThrow().getLastAuthenticatedAt();
        assertEquals(before, after);
    }

    @Test
    void rollsBackRotationWhenAccessTokenGenerationFails() {
        RefreshTokenService failingService = new RefreshTokenService(
                sessionRepo,
                new FailingAccessTokenTokenService(tokenService)
        );

        assertThrows(IllegalStateException.class, () ->
                transactionTemplate.execute(status -> failingService.refresh(rawRefreshToken)));

        LoginResponseDto recovered = refreshTokenService.refresh(rawRefreshToken);
        assertEquals(initialSessionId, recovered.sessionId());
    }

    private static final class FailingAccessTokenTokenService implements TokenService {

        private final TokenService delegate;

        private FailingAccessTokenTokenService(TokenService delegate) {
            this.delegate = delegate;
        }

        @Override
        public String generateAccessToken(User user, UUID sessionId) {
            throw new IllegalStateException("Simulated access-token generation failure");
        }

        @Override
        public String generateRefreshToken() {
            return delegate.generateRefreshToken();
        }

        @Override
        public String hashRefreshToken(String rawRefreshToken) {
            return delegate.hashRefreshToken(rawRefreshToken);
        }

        @Override
        public long getAccessTokenValiditySeconds() {
            return delegate.getAccessTokenValiditySeconds();
        }

        @Override
        public long getRefreshTokenValidityDays() {
            return delegate.getRefreshTokenValidityDays();
        }
    }
}