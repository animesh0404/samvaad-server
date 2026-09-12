package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.exception.GlobalExceptionHandler;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("admin1"))
                .andExpect(jsonPath("$[1].username").value("user1"));
    }

    @Test
    void listUsersReturnsEmptyCollection() throws Exception {
        given(userService.listUsers()).willReturn(List.of());

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
