package com.samvaad.samvaad_server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.RefreshTokenService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.auth.exception.SessionLimitExceededException;
import com.samvaad.samvaad_server.auth.token.AccessTokenClaims;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * Proves the client/session/installation identity contract end to end:
 * authentication identity is the user plus the persisted session (JWT
 * {@code sub} = userId, {@code sid} = sessionId), while
 * {@code installationId} is optional metadata that no authentication,
 * session-limit, refresh, revocation, or validation flow may require.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SessionIdentityDecoupledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo oneTimePrekeyRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo recoveryCodeRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo deviceRepo;

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
        oneTimePrekeyRepo.deleteAll();
        recoveryCodeRepo.deleteAll();
        sessionRepo.deleteAll();
        deviceRepo.deleteAll();
        userProfileRepo.deleteAll();
        userRepo.deleteAll();
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        user.setRole(UserRole.USER);
        return userRepo.save(user);
    }

    private LoginResponseDto login(String username, String installationId, ClientPlatform platform) {
        return authenticationService.login(
                new LoginRequestDto(username, "secret123", installationId, platform, null, null),
                "127.0.0.1",
                "UserAgent");
    }

    @ParameterizedTest
    @EnumSource(value = ClientPlatform.class, names = {"WEB", "TUI", "DESKTOP"})
    void webTuiAndDesktopLoginWithoutInstallationIdentity(ClientPlatform platform) {
        User user = createUser("decoupled_" + platform.name().toLowerCase());

        LoginResponseDto response = login(user.getUsername(), null, platform);

        assertNotNull(response.sessionId());
        assertNull(sessionRepo.findById(response.sessionId()).orElseThrow().getInstallationId());

        AccessTokenClaims claims = tokenService.parseAccessToken(response.accessToken());
        assertEquals(user.getUserId(), claims.userId());
        assertEquals(response.sessionId(), claims.sessionId());
    }

    @ParameterizedTest
    @EnumSource(value = ClientPlatform.class, names = {"ANDROID", "IOS"})
    void mobileInstallationMetadataPreservedWhenSupplied(ClientPlatform platform) {
        User user = createUser("decoupled_mobile_" + platform.name().toLowerCase());

        LoginResponseDto response = login(user.getUsername(), "device-metadata-1", platform);

        assertNotNull(response.sessionId());
        assertEquals("device-metadata-1",
                sessionRepo.findById(response.sessionId()).orElseThrow().getInstallationId());
    }

    @Test
    void refreshWorksWithoutInstallationIdentity() {
        User user = createUser("decoupled_refresh");
        LoginResponseDto response = login(user.getUsername(), null, ClientPlatform.WEB);

        LoginResponseDto rotated = refreshTokenService.refresh(response.refreshToken());

        assertEquals(response.sessionId(), rotated.sessionId());
        assertNotNull(rotated.accessToken());
    }

    @Test
    void logoutRevokesSessionWithoutInstallationIdentity() throws Exception {
        User user = createUser("decoupled_logout");
        LoginResponseDto response = login(user.getUsername(), null, ClientPlatform.TUI);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + response.accessToken()))
                .andExpect(status().isNoContent());

        assertNotNull(sessionRepo.findById(response.sessionId()).orElseThrow().getRevokedAt());
        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh(response.refreshToken()));
    }

    @Test
    void sessionLimitEnforcedWithoutInstallationIdentity() {
        User user = createUser("decoupled_limit");
        for (int i = 0; i < SessionService.MAX_ACTIVE_SESSIONS; i++) {
            assertNotNull(login(user.getUsername(), null, ClientPlatform.DESKTOP).sessionId());
        }

        assertThrows(SessionLimitExceededException.class,
                () -> login(user.getUsername(), null, ClientPlatform.DESKTOP));
    }

    @Test
    void httpValidationWorksWithoutInstallationIdentity() throws Exception {
        User user = createUser("decoupled_validation");
        LoginResponseDto response = login(user.getUsername(), null, ClientPlatform.WEB);

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + response.accessToken()))
                .andExpect(status().isOk());

        // Unknown bearer is still rejected; absence of installation metadata is not.
        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }
}
