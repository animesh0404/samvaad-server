package com.samvaad.samvaad_server.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.exception.BadCredentialsException;
import com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.common.logging.LogCapture;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionService;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;

import ch.qos.logback.classic.Level;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceLoggingTest {

    private static final String SECRET_PASSWORD = "s3cret-login-password-xyz";
    private static final String SECRET_REFRESH_TOKEN = "raw-refresh-token-secret-xyz";
    private static final String SECRET_ACCESS_TOKEN = "jwt-access-token-secret-xyz";

    @Mock
    private UserRepo userRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SessionService sessionService;

    @Mock
    private TokenService tokenService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        authenticationService = new AuthenticationService(
                userRepo, passwordEncoder, sessionService, tokenService, eventPublisher, transactionTemplate);
    }

    private LoginRequestDto request(String identifier) {
        return new LoginRequestDto(
                identifier, SECRET_PASSWORD, "inst-1", ClientPlatform.WEB, "Web Client", "1.0.0");
    }

    @Test
    void logsSuccessfulLoginWithoutSecrets() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));
        given(passwordEncoder.matches(SECRET_PASSWORD, "hashed-password")).willReturn(true);
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(sessionService.countActiveSessions(user)).willReturn(0L);
        given(tokenService.generateRefreshToken()).willReturn(SECRET_REFRESH_TOKEN);
        given(tokenService.hashRefreshToken(SECRET_REFRESH_TOKEN)).willReturn("hashed-refresh-token");
        given(tokenService.getRefreshTokenValidityDays()).willReturn(30L);
        given(tokenService.getAccessTokenValiditySeconds()).willReturn(86400L);
        Session session = new Session();
        session.setSessionId(sessionId);
        given(sessionService.createSession(any(), any(), any(LocalDateTime.class), any(),
                any(), any(), any(), any(), any())).willReturn(session);
        given(tokenService.generateAccessToken(user, sessionId)).willReturn(SECRET_ACCESS_TOKEN);

        try (LogCapture logs = new LogCapture(AuthenticationService.class)) {
            authenticationService.login(request("animesh"), "127.0.0.1", "Mozilla/5.0");

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains(userId.toString())
                    && e.getFormattedMessage().contains(sessionId.toString())));
            String text = logs.text();
            assertTrue(!text.contains(SECRET_PASSWORD), "password must never be logged");
            assertTrue(!text.contains(SECRET_REFRESH_TOKEN), "refresh token must never be logged");
            assertTrue(!text.contains(SECRET_ACCESS_TOKEN), "access token must never be logged");
        }
    }

    @Test
    void logsUnknownIdentifierAtWarnWithoutPassword() {
        given(userRepo.findByIdentifier("ghost")).willReturn(Optional.empty());

        try (LogCapture logs = new LogCapture(AuthenticationService.class)) {
            assertThrows(BadCredentialsException.class,
                    () -> authenticationService.login(request("ghost"), "127.0.0.1", "ua"));

            assertTrue(logs.events().stream()
                    .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("ghost")));
            assertTrue(!logs.text().contains(SECRET_PASSWORD), "password must never be logged");
        }
    }

    @Test
    void logsIncorrectPasswordAtWarnWithoutPassword() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));
        given(passwordEncoder.matches(SECRET_PASSWORD, "hashed-password")).willReturn(false);

        try (LogCapture logs = new LogCapture(AuthenticationService.class)) {
            assertThrows(IncorrectPasswordException.class,
                    () -> authenticationService.login(request("animesh"), "127.0.0.1", "ua"));

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains(userId.toString())));
            assertTrue(!logs.text().contains(SECRET_PASSWORD), "password must never be logged");
        }
    }
}
