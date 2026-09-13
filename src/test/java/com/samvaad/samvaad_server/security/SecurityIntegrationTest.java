package com.samvaad.samvaad_server.security;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException;
import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.friendrequest.FriendRequestRepo;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    private FriendRequestRepo friendRequestRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        friendRequestRepo.deleteAll();
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

    @Test
    void adminCanListUsers() throws Exception {
        User admin = createUser("list_admin", UserRole.ADMIN);
        String token = loginAs(admin).accessToken();

        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void standardUserCannotListUsers() throws Exception {
        User user = createUser("list_user", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanDeleteAnotherUser() throws Exception {
        User admin = createUser("delete_admin", UserRole.ADMIN);
        User user = createUser("delete_target", UserRole.USER);
        String token = loginAs(admin).accessToken();

        mockMvc.perform(delete("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void adminCanDeleteAnotherAdmin() throws Exception {
        User admin1 = createUser("delete_admin1", UserRole.ADMIN);
        User admin2 = createUser("delete_admin2", UserRole.ADMIN);
        String token = loginAs(admin1).accessToken();

        mockMvc.perform(delete("/api/users/{userId}", admin2.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void adminCannotDeleteSelf() throws Exception {
        User admin = createUser("delete_self_admin", UserRole.ADMIN);
        String token = loginAs(admin).accessToken();

        mockMvc.perform(delete("/api/users/{userId}", admin.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void standardUserCannotDelete() throws Exception {
        User admin = createUser("delete_std_admin", UserRole.ADMIN);
        User user = createUser("delete_std_user", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(delete("/api/users/{userId}", admin.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void standardUserCannotDeleteSelf() throws Exception {
        User user = createUser("delete_std_self", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(delete("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCannotDelete() throws Exception {
        mockMvc.perform(delete("/api/users/{userId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonexistentUserReturns404() throws Exception {
        User admin = createUser("delete_notfound_admin", UserRole.ADMIN);
        String token = loginAs(admin).accessToken();

        mockMvc.perform(delete("/api/users/{userId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletedUsersSessionIsInvalidated() throws Exception {
        User user = createUser("delete_invalidate_user", UserRole.USER);
        LoginResponseDto login = loginAs(user);
        String token = login.accessToken();
        String refreshToken = login.refreshToken();

        User admin = createUser("delete_invalidate_admin", UserRole.ADMIN);
        String adminToken = loginAs(admin).accessToken();

        mockMvc.perform(delete("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedEmailChangeReturns401() throws Exception {
        mockMvc.perform(patch("/api/users/{userId}/email", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCanChangeOwnEmail() throws Exception {
        User user = createUser("mail_self", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/email", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new-self@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getUserId().toString()))
                .andExpect(jsonPath("$.username").value("mail_self"))
                .andExpect(jsonPath("$.email").value("new-self@example.com"));

        assertTrue(userRepo.findById(user.getUserId()).orElseThrow()
                .getEmail().equals("new-self@example.com"));
    }

    @Test
    void userCannotChangeAnotherUsersEmail() throws Exception {
        User caller = createUser("mail_caller", UserRole.USER);
        User target = createUser("mail_target", UserRole.USER);
        String token = loginAs(caller).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/email", target.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"hijacked@example.com"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotChangeAnotherUsersEmail() throws Exception {
        User admin = createUser("mail_admin", UserRole.ADMIN);
        User target = createUser("mail_admintarget", UserRole.USER);
        String token = loginAs(admin).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/email", target.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"hijacked@example.com"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanChangeOwnEmail() throws Exception {
        User admin = createUser("mail_adminself", UserRole.ADMIN);
        String token = loginAs(admin).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/email", admin.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin-new@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin-new@example.com"));
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        User first = createUser("mail_first", UserRole.USER);
        User second = createUser("mail_second", UserRole.USER);
        String token = loginAs(first).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/email", first.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"MAIL_SECOND@EXAMPLE.COM"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidEmailReturns400() throws Exception {
        User user = createUser("mail_invalid", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/email", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankEmailReturns400() throws Exception {
        User user = createUser("mail_blank", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/email", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changedEmailCanBeUsedForLogin() throws Exception {
        User user = createUser("mail_login", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/email", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"login-new@example.com"}
                                """))
                .andExpect(status().isOk());

        LoginResponseDto login = authenticationService.login(
                new LoginRequestDto(
                        "login-new@example.com",
                        "secret123",
                        "inst-mail-login",
                        ClientPlatform.WEB,
                        "Test Client",
                        "1.0.0"),
                "127.0.0.1",
                "UserAgent");

        assertTrue(login.accessToken() != null && !login.accessToken().isBlank());
    }

    @Test
    void existingSessionRemainsValidAfterEmailChange() throws Exception {
        User user = createUser("mail_session", UserRole.USER);
        LoginResponseDto login = loginAs(user);
        String token = login.accessToken();
        String refreshToken = login.refreshToken();

        mockMvc.perform(patch("/api/users/{userId}/email", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"session-new@example.com"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void databaseRejectsCaseInsensitiveDuplicateEmail() {
        User first = createUser("mail_db_first", UserRole.USER);
        first.setEmail("DbUnique@Example.com");
        userRepo.saveAndFlush(first);

        User second = new User();
        second.setUsername("mail_db_second");
        second.setEmail("dbunique@example.com");
        second.setPasswordHash(passwordEncoder.encode("secret123"));
        second.setRole(UserRole.USER);

        assertThrows(DataIntegrityViolationException.class, () -> userRepo.saveAndFlush(second));
    }

    @Test
    void unauthenticatedPasswordChangeReturns401() throws Exception {
        mockMvc.perform(patch("/api/users/{userId}/password", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"new-secret"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCanChangeOwnPassword() throws Exception {
        User user = createUser("pwd_self", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/password", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"new-secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getUserId().toString()))
                .andExpect(jsonPath("$.username").value("pwd_self"));

        User reloaded = userRepo.findById(user.getUserId()).orElseThrow();
        assertTrue(passwordEncoder.matches("new-secret", reloaded.getPasswordHash()));
        assertTrue(!reloaded.getPasswordHash().contains("new-secret"));
    }

    @Test
    void adminCanChangeOwnPassword() throws Exception {
        User admin = createUser("pwd_adminself", UserRole.ADMIN);
        String token = loginAs(admin).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/password", admin.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"admin-new-secret"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void userCannotChangeAnotherUsersPassword() throws Exception {
        User caller = createUser("pwd_caller", UserRole.USER);
        User target = createUser("pwd_target", UserRole.USER);
        String token = loginAs(caller).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/password", target.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"hijacked"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotChangeAnotherUsersPassword() throws Exception {
        User admin = createUser("pwd_admin", UserRole.ADMIN);
        User target = createUser("pwd_admintarget", UserRole.USER);
        String token = loginAs(admin).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/password", target.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"hijacked"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongCurrentPasswordReturns401() throws Exception {
        User user = createUser("pwd_wrong", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/password", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-secret","newPassword":"new-secret"}
                                """))
                .andExpect(status().isUnauthorized());

        User reloaded = userRepo.findById(user.getUserId()).orElseThrow();
        assertTrue(passwordEncoder.matches("secret123", reloaded.getPasswordHash()));
    }

    @Test
    void blankCurrentPasswordReturns400() throws Exception {
        User user = createUser("pwd_blankcurrent", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/password", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"","newPassword":"new-secret"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankNewPasswordReturns400() throws Exception {
        User user = createUser("pwd_blanknew", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/password", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingPasswordFieldsReturn400() throws Exception {
        User user = createUser("pwd_missing", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/password", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oldPasswordStopsWorkingAndNewPasswordWorksAfterChange() throws Exception {
        User user = createUser("pwd_rotation", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(patch("/api/users/{userId}/password", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"rotated-secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("pwd_rotation"))
                .andExpect(jsonPath("$.email").value("pwd_rotation@example.com"));

        assertThrows(IncorrectPasswordException.class, () -> authenticationService.login(
                new LoginRequestDto(
                        "pwd_rotation",
                        "secret123",
                        "inst-pwd-rotation",
                        ClientPlatform.WEB,
                        "Test Client",
                        "1.0.0"),
                "127.0.0.1",
                "UserAgent"));

        LoginResponseDto login = authenticationService.login(
                new LoginRequestDto(
                        "pwd_rotation",
                        "rotated-secret",
                        "inst-pwd-rotation",
                        ClientPlatform.WEB,
                        "Test Client",
                        "1.0.0"),
                "127.0.0.1",
                "UserAgent");

        assertTrue(login.accessToken() != null && !login.accessToken().isBlank());

        User reloaded = userRepo.findById(user.getUserId()).orElseThrow();
        assertTrue(reloaded.getUsername().equals("pwd_rotation"));
        assertTrue(reloaded.getEmail().equals("pwd_rotation@example.com"));
        assertTrue(reloaded.getRole() == UserRole.USER);
    }

    @Test
    void existingSessionRemainsValidAfterPasswordChange() throws Exception {
        User user = createUser("pwd_session", UserRole.USER);
        LoginResponseDto login = loginAs(user);
        String token = login.accessToken();
        String refreshToken = login.refreshToken();

        mockMvc.perform(patch("/api/users/{userId}/password", user.getUserId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"secret123","newPassword":"session-secret"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/{userId}", user.getUserId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void unauthenticatedLookupReturns401() throws Exception {
        mockMvc.perform(get("/api/users/lookup").param("username", "alice"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lookupByExactUsernameReturns200() throws Exception {
        User user = createUser("lookup_alice", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(get("/api/users/lookup").param("username", "lookup_alice")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getUserId().toString()))
                .andExpect(jsonPath("$.username").value("lookup_alice"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist());
    }

    @Test
    void lookupMatchesCaseInsensitively() throws Exception {
        User user = createUser("lookup_bob", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(get("/api/users/lookup").param("username", "LOOKUP_BOB")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getUserId().toString()))
                .andExpect(jsonPath("$.username").value("lookup_bob"));
    }

    @Test
    void lookupSelfReturns200() throws Exception {
        User user = createUser("lookup_self", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(get("/api/users/lookup").param("username", "lookup_self")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getUserId().toString()));
    }

    @Test
    void lookupPartialUsernameReturns404() throws Exception {
        User user = createUser("lookup_partial", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(get("/api/users/lookup").param("username", "lookup_part")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/users/lookup").param("username", "ookup_partial")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/users/lookup").param("username", "lookup_partial_extra")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void lookupNonexistentUsernameReturns404() throws Exception {
        User user = createUser("lookup_caller", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(get("/api/users/lookup").param("username", "ghost")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void lookupMissingUsernameReturns400() throws Exception {
        User user = createUser("lookup_noparam", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(get("/api/users/lookup")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lookupBlankUsernameReturns400() throws Exception {
        User user = createUser("lookup_blank", UserRole.USER);
        String token = loginAs(user).accessToken();

        mockMvc.perform(get("/api/users/lookup").param("username", "   ")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}