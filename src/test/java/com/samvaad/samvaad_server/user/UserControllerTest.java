package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.exception.GlobalExceptionHandler;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
