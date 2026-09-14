package com.samvaad.samvaad_server.session;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionInstallationIdNullableIntegrationTest {

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        sessionRepo.deleteAll();
        userProfileRepo.deleteAll();
        userRepo.deleteAll();

        User user = new User();
        user.setUsername("nullable_inst_user");
        user.setEmail("nullable-inst@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        userRepo.save(user);
    }

    @Test
    void loginWithoutInstallationIdPersistsNullSession() {
        LoginResponseDto response = authenticationService.login(
                new LoginRequestDto(
                        "nullable_inst_user",
                        "secret123",
                        null,
                        ClientPlatform.TUI,
                        null,
                        null),
                "127.0.0.1",
                "UserAgent");

        assertNotNull(response.sessionId());
        Session persisted = sessionRepo.findById(response.sessionId()).orElseThrow();
        assertNull(persisted.getInstallationId());
    }

    @Test
    void loginWithBlankInstallationIdNormalizesToNull() {
        LoginResponseDto response = authenticationService.login(
                new LoginRequestDto(
                        "nullable_inst_user",
                        "secret123",
                        "   ",
                        ClientPlatform.WEB,
                        null,
                        null),
                "127.0.0.1",
                "UserAgent");

        assertNotNull(response.sessionId());
        Session persisted = sessionRepo.findById(response.sessionId()).orElseThrow();
        assertNull(persisted.getInstallationId());
    }

    @Test
    void loginWithInstallationIdPersistsValueUnchanged() {
        LoginResponseDto response = authenticationService.login(
                new LoginRequestDto(
                        "nullable_inst_user",
                        "secret123",
                        "android-inst-1",
                        ClientPlatform.ANDROID,
                        "Samvaad Android",
                        "1.0.0"),
                "127.0.0.1",
                "UserAgent");

        assertNotNull(response.sessionId());
        Session persisted = sessionRepo.findById(response.sessionId()).orElseThrow();
        assertEquals("android-inst-1", persisted.getInstallationId());
    }

    @Test
    void directSessionPersistWithNullInstallationId() {
        User user = userRepo.findByIdentifier("nullable_inst_user").orElseThrow();

        Session session = new Session();
        session.setUser(user);
        session.setRefreshTokenHash("direct-null-inst-hash");
        session.setRefreshTokenExpiresAt(LocalDateTime.now().plusDays(30));
        session.setInstallationId(null);
        session.setClientPlatform(ClientPlatform.TUI);
        session.setLastAuthenticatedAt(LocalDateTime.now());
        Session saved = sessionRepo.saveAndFlush(session);

        Session reloaded = sessionRepo.findById(saved.getSessionId()).orElseThrow();
        assertNull(reloaded.getInstallationId());
    }
}
