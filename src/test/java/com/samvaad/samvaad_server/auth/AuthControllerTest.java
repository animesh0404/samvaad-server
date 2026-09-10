package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.BadCredentialsException;
import com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException;
import com.samvaad.samvaad_server.auth.exception.SessionLimitExceededException;
import com.samvaad.samvaad_server.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new AuthController(authenticationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void logsInSuccessfullyWithValidPayload() throws Exception {
        UUID sessionId = UUID.randomUUID();
        LoginResponseDto response = new LoginResponseDto(
                "mock-jwt-token",
                "mock-refresh-token",
                86400L,
                sessionId
        );

        given(authenticationService.login(any(LoginRequestDto.class), any(), any()))
                .willReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identifier": "animesh",
                                  "password": "secretpassword",
                                  "installationId": "device-123",
                                  "clientPlatform": "WEB",
                                  "clientName": "Samvaad Web",
                                  "clientVersion": "1.0.0"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock-jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("mock-refresh-token"))
                .andExpect(jsonPath("$.expiresIn").value(86400))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()));
    }

    @Test
    void rejectsInvalidPayloadWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identifier": "",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsGenericUnauthorizedOnBadCredentials() throws Exception {
        given(authenticationService.login(any(), any(), any()))
                .willThrow(new BadCredentialsException("Invalid credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identifier": "unknown",
                                  "password": "wrong",
                                  "installationId": "device-123",
                                  "clientPlatform": "WEB"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void returnsSpecificUnauthorizedOnIncorrectPassword() throws Exception {
        given(authenticationService.login(any(), any(), any()))
                .willThrow(new IncorrectPasswordException("Incorrect password"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identifier": "animesh",
                                  "password": "wrongpassword",
                                  "installationId": "device-123",
                                  "clientPlatform": "WEB"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Incorrect password"));
    }

    @Test
    void returnsGenericUnauthorizedWhenSessionLimitReached() throws Exception {
        given(authenticationService.login(any(), any(), any()))
                .willThrow(new SessionLimitExceededException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identifier": "animesh",
                                  "password": "correctpassword",
                                  "installationId": "device-123",
                                  "clientPlatform": "WEB"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }
}
