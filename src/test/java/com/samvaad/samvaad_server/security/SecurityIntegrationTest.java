package com.samvaad.samvaad_server.security;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private TokenService tokenService;

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
    }

    private User createUser(String username, UserRole role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        user.setRole(role);
        return userRepo.save(user);
    }

    private LoginResponseDto loginAs(User user) {
        return authenticationService.login(
                new LoginRequestDto(
                        user.getUsername(),
                        "secret123",
                        "inst-" + user.getUsername(),
                        ClientPlatform.WEB,
                        "Test Client",
                        "1.0.0"),
                "127.0.0.1",
                "UserAgent");
    }

    @Test
    void protectedEndpointWithoutAuthenticationReturns401() throws Exception {
        mockMvc.perform(get("/api/users/{userId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void provisioningWithoutAuthenticationReturns401() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"newbie","password":"secret123"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserWithoutAdminAuthorityReturns403() throws Exception {
        User user = createUser("standard_user", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"newbie","password":"secret123"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanProvisionUserForcedToUserRoleWithHashedPassword() throws Exception {
        User admin = createUser("bootstrap_admin", UserRole.ADMIN);
        String token = loginAs(admin).accessToken();

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"provisioned","password":"secret123","email":"provisioned@example.com"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("provisioned"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User provisioned = userRepo.findByIdentifier("provisioned").orElseThrow();
        assertTrue(provisioned.getPasswordHash().startsWith("$2"));
        assertTrue(!provisioned.getPasswordHash().contains("secret123"));
    }

    @Test
    void missingSessionReturns401() throws Exception {
        User user = createUser("missing_session_user", UserRole.USER);
        String token = tokenService.generateAccessToken(user, UUID.randomUUID());

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedSessionReturns401() throws Exception {
        User user = createUser("revoked_session_user", UserRole.USER);
        LoginResponseDto login = loginAs(user);

        Session session = sessionRepo.findById(login.sessionId()).orElseThrow();
        session.setRevokedAt(LocalDateTime.now());
        sessionRepo.saveAndFlush(session);

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredSessionReturns401() throws Exception {
        User user = createUser("expired_session_user", UserRole.USER);
        LoginResponseDto login = loginAs(user);

        Session session = sessionRepo.findById(login.sessionId()).orElseThrow();
        session.setRefreshTokenExpiresAt(LocalDateTime.now().minusSeconds(1));
        sessionRepo.saveAndFlush(session);

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void subjectSessionMismatchReturns401() throws Exception {
        User sessionOwner = createUser("session_owner", UserRole.USER);
        User otherUser = createUser("other_user", UserRole.USER);
        LoginResponseDto login = loginAs(sessionOwner);

        String mismatchedToken = tokenService.generateAccessToken(otherUser, login.sessionId());

        mockMvc.perform(get("/api/users/{userId}", otherUser.getUserId())
                        .header("Authorization", "Bearer " + mismatchedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanReadOwnAccount() throws Exception {
        User user = createUser("self_reader", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getUserId().toString()))
                .andExpect(jsonPath("$.username").value("self_reader"));
    }

    @Test
    void standardUserCannotReadAnotherUsersAccount() throws Exception {
        User caller = createUser("caller_user", UserRole.USER);
        User target = createUser("target_user", UserRole.USER);
        String token = loginAs(caller).accessToken();

        mockMvc.perform(get("/api/users/{userId}", target.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutReturns204AndInvalidatesToken() throws Exception {
        User user = createUser("logout_user", UserRole.USER);
        LoginResponseDto login = loginAs(user);
        String token = login.accessToken();
        UUID sessionId = login.sessionId();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedLogoutReturns204() throws Exception {
        User user = createUser("logout_repeat_user", UserRole.USER);
        LoginResponseDto login = loginAs(user);
        String token = login.accessToken();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void refreshTokenAfterLogoutIsRejected() throws Exception {
        User user = createUser("refresh_logout_user", UserRole.USER);
        LoginResponseDto login = loginAs(user);
        String token = login.accessToken();
        String refreshToken = login.refreshToken();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherSessionRemainsValidAfterLogout() throws Exception {
        User user = createUser("multi_session_user", UserRole.USER);
        LoginResponseDto login1 = loginAs(user);
        String token1 = login1.accessToken();
        UUID sessionId1 = login1.sessionId();

        Session secondSession = new Session();
        secondSession.setUser(user);
        secondSession.setRefreshTokenHash(tokenService.hashRefreshToken(tokenService.generateRefreshToken()));
        secondSession.setRefreshTokenExpiresAt(LocalDateTime.now().plusDays(30));
        secondSession.setInstallationId("inst-second");
        secondSession.setClientPlatform(ClientPlatform.WEB);
        secondSession.setLastAuthenticatedAt(LocalDateTime.now());
        sessionRepo.save(secondSession);

        String secondToken = tokenService.generateAccessToken(user, secondSession.getSessionId());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isUnauthorized());
    }
}