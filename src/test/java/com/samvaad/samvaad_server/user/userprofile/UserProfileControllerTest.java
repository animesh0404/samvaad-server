package com.samvaad.samvaad_server.user.userprofile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        mockMvc = standaloneSetup(new UserProfileController(userProfileService)).build();
    }

    @Test
    void updatesProfileThroughTheApi() throws Exception {
        UUID userId = UUID.randomUUID();
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
}
