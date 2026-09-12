package com.samvaad.samvaad_server.user.userprofile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.samvaad.samvaad_server.exception.GlobalExceptionHandler;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.user.UserRole;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class UserProfileControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserProfileService userProfileService;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new UserProfileController(userProfileService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID userId, UserRole role) {
        AuthenticatedUser caller = new AuthenticatedUser(userId, role, UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        caller,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @Test
    void updatesProfileThroughTheApi() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(userId, UserRole.USER);

        UserProfileDto response = new UserProfileDto();
        response.setUserId(userId);
        response.setDisplayName("Animesh");
        response.setBio("Building Samvaad");

        given(userProfileService.updateProfile(eq(userId), any(UserProfileUpdateDto.class)))
                .willReturn(response);

        mockMvc.perform(patch("/api/users/{userId}/profile", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Animesh","bio":"Building Samvaad"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.displayName").value("Animesh"))
                .andExpect(jsonPath("$.bio").value("Building Samvaad"));

        then(userProfileService).should().updateProfile(eq(userId), any(UserProfileUpdateDto.class));
    }

    @Test
    void allowsUserToReadOwnProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticateAs(userId, UserRole.USER);

        UserProfileDto response = new UserProfileDto();
        response.setUserId(userId);
        given(userProfileService.getProfile(userId)).willReturn(response);

        mockMvc.perform(get("/api/users/{userId}/profile", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()));

        then(userProfileService).should().getProfile(userId);
    }

    @Test
    void allowsAdminToReadAnotherUsersProfile() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        authenticateAs(adminId, UserRole.ADMIN);

        UserProfileDto response = new UserProfileDto();
        response.setUserId(targetId);
        given(userProfileService.getProfile(targetId)).willReturn(response);

        mockMvc.perform(get("/api/users/{userId}/profile", targetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(targetId.toString()));

        then(userProfileService).should().getProfile(targetId);
    }

    @Test
    void forbidsAdminFromEditingAnotherUsersProfile() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        authenticateAs(adminId, UserRole.ADMIN);

        mockMvc.perform(patch("/api/users/{userId}/profile", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Hijacked"}
                                """))
                .andExpect(status().isForbidden());

        then(userProfileService).shouldHaveNoInteractions();
    }

    @Test
    void forbidsUserFromReadingAnotherUsersProfile() throws Exception {
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        authenticateAs(callerId, UserRole.USER);

        mockMvc.perform(get("/api/users/{userId}/profile", targetId))
                .andExpect(status().isForbidden());

        then(userProfileService).shouldHaveNoInteractions();
    }

    @Test
    void forbidsUserFromEditingAnotherUsersProfile() throws Exception {
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        authenticateAs(callerId, UserRole.USER);

        mockMvc.perform(patch("/api/users/{userId}/profile", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Hijacked"}
                                """))
                .andExpect(status().isForbidden());

        then(userProfileService).shouldHaveNoInteractions();
    }
}