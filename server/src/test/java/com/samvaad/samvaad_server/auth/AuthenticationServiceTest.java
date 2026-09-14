package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.event.LoginBlockedDueToSessionLimitEvent;
import com.samvaad.samvaad_server.auth.exception.BadCredentialsException;
import com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException;
import com.samvaad.samvaad_server.auth.exception.SessionLimitExceededException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionService;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

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
                userRepo,
                passwordEncoder,
                sessionService,
                tokenService,
                eventPublisher,
                transactionTemplate
        );
    }

    @Test
    void successfullyLogsInWithValidCredentials() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");

        LoginRequestDto request = new LoginRequestDto(
                "animesh",
                "correct-password",
                "inst-1",
                ClientPlatform.WEB,
                "Web Client",
                "1.0.0"
        );

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("correct-password", "hashed-password")).willReturn(true);

        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(sessionService.countActiveSessions(user)).willReturn(2L);
        given(tokenService.generateRefreshToken()).willReturn("raw-refresh-token");
        given(tokenService.hashRefreshToken("raw-refresh-token")).willReturn("hashed-refresh-token");
        given(tokenService.getRefreshTokenValidityDays()).willReturn(30L);
        given(tokenService.getAccessTokenValiditySeconds()).willReturn(86400L);

        Session session = new Session();
        session.setSessionId(sessionId);
        given(sessionService.createSession(
                eq(user),
                eq("hashed-refresh-token"),
                any(LocalDateTime.class),
                eq("inst-1"),
                eq(ClientPlatform.WEB),
                eq("Web Client"),
                eq("1.0.0"),
                eq("127.0.0.1"),
                eq("Mozilla/5.0")
        )).willReturn(session);

        given(tokenService.generateAccessToken(user, sessionId)).willReturn("jwt-access-token");

        LoginResponseDto response = authenticationService.login(request, "127.0.0.1", "Mozilla/5.0");

        assertNotNull(response);
        assertEquals("jwt-access-token", response.accessToken());
        assertEquals("raw-refresh-token", response.refreshToken());
        assertEquals(86400L, response.expiresIn());
        assertEquals(sessionId, response.sessionId());

        // Verify password check happened strictly BEFORE acquiring the database lock
        InOrder inOrder = inOrder(passwordEncoder, userRepo);
        inOrder.verify(passwordEncoder).matches("correct-password", "hashed-password");
        inOrder.verify(userRepo).findByIdWithLock(userId);
    }

    @Test
    void failsWhenIdentifierDoesNotExist() {
        LoginRequestDto request = new LoginRequestDto(
                "unknown",
                "password",
                "inst-1",
                ClientPlatform.WEB,
                null,
                null
        );

        given(userRepo.findByIdentifier("unknown")).willReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () ->
                authenticationService.login(request, "127.0.0.1", "UserAgent")
        );

        then(passwordEncoder).shouldHaveNoInteractions();
        then(transactionTemplate).shouldHaveNoInteractions();
    }

    @Test
    void failsWhenPasswordDoesNotMatch() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");

        LoginRequestDto request = new LoginRequestDto(
                "animesh",
                "wrong-password",
                "inst-1",
                ClientPlatform.WEB,
                null,
                null
        );

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong-password", "hashed-password")).willReturn(false);

        assertThrows(IncorrectPasswordException.class, () ->
                authenticationService.login(request, "127.0.0.1", "UserAgent")
        );

        then(transactionTemplate).shouldHaveNoInteractions();
        then(userRepo).should(never()).findByIdWithLock(any());
    }

    @Test
    void failsWhenUserHasNoPasswordConfigured() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash(null);

        LoginRequestDto request = new LoginRequestDto(
                "animesh",
                "password",
                "inst-1",
                ClientPlatform.WEB,
                null,
                null
        );

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));

        assertThrows(BadCredentialsException.class, () ->
                authenticationService.login(request, "127.0.0.1", "UserAgent")
        );

        then(passwordEncoder).shouldHaveNoInteractions();
        then(transactionTemplate).shouldHaveNoInteractions();
    }

    @Test
    void blocksLoginAndPublishesEventWhenFiveActiveSessionsExist() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");

        LoginRequestDto request = new LoginRequestDto(
                "animesh",
                "correct-password",
                "inst-1",
                ClientPlatform.WEB,
                "Web Client",
                "1.0.0"
        );

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("correct-password", "hashed-password")).willReturn(true);

        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(sessionService.countActiveSessions(user)).willReturn(5L);

        assertThrows(SessionLimitExceededException.class, () ->
                authenticationService.login(request, "127.0.0.1", "Mozilla/5.0")
        );

        ArgumentCaptor<LoginBlockedDueToSessionLimitEvent> eventCaptor =
                ArgumentCaptor.forClass(LoginBlockedDueToSessionLimitEvent.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());

        LoginBlockedDueToSessionLimitEvent event = eventCaptor.getValue();
        assertEquals(userId, event.userId());
        assertEquals("inst-1", event.installationId());
        assertEquals(ClientPlatform.WEB, event.clientPlatform());
        assertEquals("Web Client", event.clientName());
        assertEquals("1.0.0", event.clientVersion());
        assertEquals("127.0.0.1", event.ipAddress());
        assertEquals("Mozilla/5.0", event.userAgent());

        then(sessionService).should(never()).createSession(any(), any(), any(), any(), any(), any(), any(), any(), any());
        then(tokenService).should(never()).generateAccessToken(any(), any());
    }

    @Test
    void propagatesExceptionWhenTokenGenerationFailsToEnsureRollback() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");

        LoginRequestDto request = new LoginRequestDto(
                "animesh",
                "correct-password",
                "inst-1",
                ClientPlatform.WEB,
                "Web Client",
                "1.0.0"
        );

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("correct-password", "hashed-password")).willReturn(true);

        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(sessionService.countActiveSessions(user)).willReturn(2L);
        given(tokenService.generateRefreshToken()).willReturn("raw-refresh-token");
        given(tokenService.hashRefreshToken("raw-refresh-token")).willReturn("hashed-refresh-token");
        given(tokenService.getRefreshTokenValidityDays()).willReturn(30L);

        Session session = new Session();
        session.setSessionId(sessionId);
        given(sessionService.createSession(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(session);

        given(tokenService.generateAccessToken(user, sessionId))
                .willThrow(new IllegalStateException("Key failure"));

        assertThrows(IllegalStateException.class, () ->
                authenticationService.login(request, "127.0.0.1", "Mozilla/5.0")
        );
    }

    @Test
    void successfullyLogsInWithoutInstallationId() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");

        LoginRequestDto request = new LoginRequestDto(
                "animesh",
                "correct-password",
                null,
                ClientPlatform.WEB,
                "Web Client",
                "1.0.0"
        );

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("correct-password", "hashed-password")).willReturn(true);

        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(sessionService.countActiveSessions(user)).willReturn(2L);
        given(tokenService.generateRefreshToken()).willReturn("raw-refresh-token");
        given(tokenService.hashRefreshToken("raw-refresh-token")).willReturn("hashed-refresh-token");
        given(tokenService.getRefreshTokenValidityDays()).willReturn(30L);
        given(tokenService.getAccessTokenValiditySeconds()).willReturn(86400L);

        Session session = new Session();
        session.setSessionId(sessionId);
        given(sessionService.createSession(
                eq(user),
                eq("hashed-refresh-token"),
                any(LocalDateTime.class),
                isNull(),
                eq(ClientPlatform.WEB),
                eq("Web Client"),
                eq("1.0.0"),
                eq("127.0.0.1"),
                eq("Mozilla/5.0")
        )).willReturn(session);

        given(tokenService.generateAccessToken(user, sessionId)).willReturn("jwt-access-token");

        LoginResponseDto response = authenticationService.login(request, "127.0.0.1", "Mozilla/5.0");

        assertNotNull(response);
        assertEquals("jwt-access-token", response.accessToken());
        assertEquals(sessionId, response.sessionId());
    }

    @Test
    void normalizesBlankInstallationIdToNull() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");

        LoginRequestDto request = new LoginRequestDto(
                "animesh",
                "correct-password",
                "   ",
                ClientPlatform.TUI,
                null,
                null
        );

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("correct-password", "hashed-password")).willReturn(true);

        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(sessionService.countActiveSessions(user)).willReturn(0L);
        given(tokenService.generateRefreshToken()).willReturn("raw-refresh-token");
        given(tokenService.hashRefreshToken("raw-refresh-token")).willReturn("hashed-refresh-token");
        given(tokenService.getRefreshTokenValidityDays()).willReturn(30L);
        given(tokenService.getAccessTokenValiditySeconds()).willReturn(86400L);

        Session session = new Session();
        session.setSessionId(sessionId);
        given(sessionService.createSession(
                eq(user),
                eq("hashed-refresh-token"),
                any(LocalDateTime.class),
                isNull(),
                eq(ClientPlatform.TUI),
                isNull(),
                isNull(),
                eq("127.0.0.1"),
                eq("Mozilla/5.0")
        )).willReturn(session);

        given(tokenService.generateAccessToken(user, sessionId)).willReturn("jwt-access-token");

        LoginResponseDto response = authenticationService.login(request, "127.0.0.1", "Mozilla/5.0");

        assertNotNull(response);
        assertEquals(sessionId, response.sessionId());
    }

    @Test
    void blocksLoginWithNullInstallationIdAndPublishesNullInEvent() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");

        LoginRequestDto request = new LoginRequestDto(
                "animesh",
                "correct-password",
                null,
                ClientPlatform.WEB,
                null,
                null
        );

        given(userRepo.findByIdentifier("animesh")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("correct-password", "hashed-password")).willReturn(true);

        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(sessionService.countActiveSessions(user)).willReturn(5L);

        assertThrows(SessionLimitExceededException.class, () ->
                authenticationService.login(request, "127.0.0.1", "Mozilla/5.0")
        );

        ArgumentCaptor<LoginBlockedDueToSessionLimitEvent> eventCaptor =
                ArgumentCaptor.forClass(LoginBlockedDueToSessionLimitEvent.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());

        LoginBlockedDueToSessionLimitEvent event = eventCaptor.getValue();
        assertEquals(userId, event.userId());
        assertNull(event.installationId());

        then(sessionService).should(never()).createSession(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
