package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException;
import com.samvaad.samvaad_server.exception.GlobalExceptionHandler;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.doThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new UserController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UserRole role, UUID userId) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, role, UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @Test
    void adminCanListUsers() throws Exception {
        UserDto admin = new UserDto();
        admin.setUserId(UUID.randomUUID());
        admin.setUsername("admin1");
        admin.setEmail("admin1@example.com");
        admin.setRole(UserRole.ADMIN);

        UserDto user = new UserDto();
        user.setUserId(UUID.randomUUID());
        user.setUsername("user1");
        user.setEmail("user1@example.com");
        user.setRole(UserRole.USER);

        given(userService.listUsers()).willReturn(List.of(admin, user));
        authenticateAs(UserRole.ADMIN, UUID.randomUUID());

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("admin1"))
                .andExpect(jsonPath("$[1].username").value("user1"));
    }

    @Test
    void listUsersReturnsEmptyCollection() throws Exception {
        given(userService.listUsers()).willReturn(List.of());
        authenticateAs(UserRole.ADMIN, UUID.randomUUID());

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void adminCanDeleteUser() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        authenticateAs(UserRole.ADMIN, adminId);
        willDoNothing().given(userService).deleteUser(eq(targetId));

        mockMvc.perform(delete("/api/users/{userId}", targetId))
                .andExpect(status().isNoContent());
    }

    @Test
    void standardUserCannotDelete() throws Exception {
        UserDto user = new UserDto();
        user.setUserId(UUID.randomUUID());
        user.setUsername("user1");
        user.setRole(UserRole.USER);

        authenticateAs(UserRole.USER, UUID.randomUUID());

        mockMvc.perform(delete("/api/users/{userId}", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotDeleteSelf() throws Exception {
        UUID adminId = UUID.randomUUID();
        authenticateAs(UserRole.ADMIN, adminId);

        mockMvc.perform(delete("/api/users/{userId}", adminId))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonexistentUserReturns404() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        authenticateAs(UserRole.ADMIN, adminId);
        doThrow(new UserNotFoundException(targetId)).when(userService).deleteUser(eq(targetId));

        mockMvc.perform(delete("/api/users/{userId}", targetId))
                .andExpect(status().isNotFound());
    }

    @Test
    void userCanChangeOwnEmail() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        UserDto updated = new UserDto();
        updated.setUserId(userId);
        updated.setUsername("user1");
        updated.setEmail("new@example.com");
        updated.setRole(UserRole.USER);

        given(userService.changeEmail(eq(userId), eq("new@example.com"))).willReturn(updated);

        mockMvc.perform(patch("/api/users/{userId}/email", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.username").value("user1"))
                .andExpect(jsonPath("$.email").value("new@example.com"));

        then(userService).should().changeEmail(eq(userId), eq("new@example.com"));
    }

    @Test
    void adminCanChangeOwnEmail() throws Exception {
        UUID adminId = UUID.randomUUID();
        authenticateAs(UserRole.ADMIN, adminId);

        UserDto updated = new UserDto();
        updated.setUserId(adminId);
        updated.setUsername("admin1");
        updated.setEmail("admin-new@example.com");
        updated.setRole(UserRole.ADMIN);

        given(userService.changeEmail(eq(adminId), eq("admin-new@example.com"))).willReturn(updated);

        mockMvc.perform(patch("/api/users/{userId}/email", adminId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin-new@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin-new@example.com"));
    }

    @Test
    void userCannotChangeAnotherUsersEmail() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        mockMvc.perform(patch("/api/users/{userId}/email", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"hijacked@example.com"}
                                """))
                .andExpect(status().isForbidden());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void adminCannotChangeAnotherUsersEmail() throws Exception {
        authenticateAs(UserRole.ADMIN, UUID.randomUUID());

        mockMvc.perform(patch("/api/users/{userId}/email", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"hijacked@example.com"}
                                """))
                .andExpect(status().isForbidden());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void invalidEmailReturns400() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        mockMvc.perform(patch("/api/users/{userId}/email", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void blankEmailReturns400() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        mockMvc.perform(patch("/api/users/{userId}/email", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":""}
                                """))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void nullEmailReturns400() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        mockMvc.perform(patch("/api/users/{userId}/email", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":null}
                                """))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void tooLongEmailReturns400() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        String tooLong = "a".repeat(310) + "@example.com";

        mockMvc.perform(patch("/api/users/{userId}/email", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);
        given(userService.changeEmail(eq(userId), eq("taken@example.com")))
                .willThrow(new EmailAlreadyExistsException("taken@example.com"));

        mockMvc.perform(patch("/api/users/{userId}/email", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"taken@example.com"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already in use: taken@example.com"));
    }

    @Test
    void changeEmailNonexistentUserReturns404() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);
        given(userService.changeEmail(eq(userId), eq("new@example.com")))
                .willThrow(new UserNotFoundException(userId));

        mockMvc.perform(patch("/api/users/{userId}/email", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@example.com"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameEmailReturns200() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        UserDto unchanged = new UserDto();
        unchanged.setUserId(userId);
        unchanged.setUsername("user1");
        unchanged.setEmail("same@example.com");
        unchanged.setRole(UserRole.USER);

        given(userService.changeEmail(eq(userId), eq("same@example.com"))).willReturn(unchanged);

        mockMvc.perform(patch("/api/users/{userId}/email", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"same@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("same@example.com"));
    }

    @Test
    void userCanChangeOwnPassword() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        UserDto updated = new UserDto();
        updated.setUserId(userId);
        updated.setUsername("user1");
        updated.setEmail("user1@example.com");
        updated.setRole(UserRole.USER);

        given(userService.changePassword(eq(userId), eq("old-secret"), eq("new-secret")))
                .willReturn(updated);

        mockMvc.perform(patch("/api/users/{userId}/password", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-secret","newPassword":"new-secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.username").value("user1"));

        then(userService).should().changePassword(eq(userId), eq("old-secret"), eq("new-secret"));
    }

    @Test
    void adminCanChangeOwnPassword() throws Exception {
        UUID adminId = UUID.randomUUID();
        authenticateAs(UserRole.ADMIN, adminId);

        UserDto updated = new UserDto();
        updated.setUserId(adminId);
        updated.setUsername("admin1");
        updated.setRole(UserRole.ADMIN);

        given(userService.changePassword(eq(adminId), eq("old-secret"), eq("new-secret")))
                .willReturn(updated);

        mockMvc.perform(patch("/api/users/{userId}/password", adminId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-secret","newPassword":"new-secret"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void userCannotChangeAnotherUsersPassword() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        mockMvc.perform(patch("/api/users/{userId}/password", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-secret","newPassword":"new-secret"}
                                """))
                .andExpect(status().isForbidden());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void adminCannotChangeAnotherUsersPassword() throws Exception {
        authenticateAs(UserRole.ADMIN, UUID.randomUUID());

        mockMvc.perform(patch("/api/users/{userId}/password", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-secret","newPassword":"new-secret"}
                                """))
                .andExpect(status().isForbidden());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void wrongCurrentPasswordReturns401() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);
        given(userService.changePassword(eq(userId), eq("wrong-secret"), eq("new-secret")))
                .willThrow(new IncorrectPasswordException("Incorrect password"));

        mockMvc.perform(patch("/api/users/{userId}/password", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-secret","newPassword":"new-secret"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankCurrentPasswordReturns400() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        mockMvc.perform(patch("/api/users/{userId}/password", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"","newPassword":"new-secret"}
                                """))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void missingCurrentPasswordReturns400() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        mockMvc.perform(patch("/api/users/{userId}/password", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"new-secret"}
                                """))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void blankNewPasswordReturns400() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        mockMvc.perform(patch("/api/users/{userId}/password", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-secret","newPassword":""}
                                """))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void missingNewPasswordReturns400() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        mockMvc.perform(patch("/api/users/{userId}/password", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-secret"}
                                """))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void changePasswordNonexistentUserReturns404() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);
        given(userService.changePassword(eq(userId), eq("old-secret"), eq("new-secret")))
                .willThrow(new UserNotFoundException(userId));

        mockMvc.perform(patch("/api/users/{userId}/password", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-secret","newPassword":"new-secret"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void samePasswordReturns200() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(UserRole.USER, userId);

        UserDto updated = new UserDto();
        updated.setUserId(userId);
        updated.setUsername("user1");
        updated.setRole(UserRole.USER);

        given(userService.changePassword(eq(userId), eq("same-secret"), eq("same-secret")))
                .willReturn(updated);

        mockMvc.perform(patch("/api/users/{userId}/password", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"same-secret","newPassword":"same-secret"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void lookupByExactUsernameReturns200() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        UserLookupDto found = new UserLookupDto(UUID.randomUUID(), "alice");
        given(userService.lookupByUsername(eq("alice"))).willReturn(found);

        mockMvc.perform(get("/api/users/lookup").param("username", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(found.getUserId().toString()))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist());

        then(userService).should().lookupByUsername(eq("alice"));
    }

    @Test
    void lookupByUsernameIsCaseInsensitive() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        UserLookupDto found = new UserLookupDto(UUID.randomUUID(), "alice");
        given(userService.lookupByUsername(eq("ALICE"))).willReturn(found);

        mockMvc.perform(get("/api/users/lookup").param("username", "ALICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void lookupNonexistentUsernameReturns404() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        given(userService.lookupByUsername(eq("ghost")))
                .willThrow(new UserNotFoundException("ghost"));

        mockMvc.perform(get("/api/users/lookup").param("username", "ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void lookupMissingUsernameReturns400() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        mockMvc.perform(get("/api/users/lookup"))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }

    @Test
    void lookupBlankUsernameReturns400() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        mockMvc.perform(get("/api/users/lookup").param("username", "   "))
                .andExpect(status().isBadRequest());

        then(userService).shouldHaveNoInteractions();
    }
}
