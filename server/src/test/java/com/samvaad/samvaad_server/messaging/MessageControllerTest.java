package com.samvaad.samvaad_server.messaging;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.exception.GlobalExceptionHandler;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.UserRole;

@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    private MockMvc mockMvc;

    @Mock
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MessageController(messageService))
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

    private MessageDto message(UUID messageId, UUID conversationId, UUID senderId) {
        MessageDto dto = new MessageDto();
        dto.setMessageId(messageId);
        dto.setConversationId(conversationId);
        dto.setSenderUserId(senderId);
        dto.setSequenceNumber(1L);
        dto.setContent("Hello");
        dto.setServerTimestamp(LocalDateTime.now());
        dto.setRequestId(UUID.randomUUID());
        return dto;
    }

    @Test
    void sendMessageReturns201() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, senderId);

        MessageDto dto = message(UUID.randomUUID(), UUID.randomUUID(), senderId);
        given(messageService.sendMessage(eq(senderId), eq("bob"), eq("Hello"), eq(requestId)))
                .willReturn(new SendMessageResult(dto, true));

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","content":"Hello","requestId":"%s"}
                                """.formatted(requestId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Hello"))
                .andExpect(jsonPath("$.sequenceNumber").value(1))
                .andExpect(jsonPath("$.senderUserId").value(senderId.toString()));

        then(messageService).should().sendMessage(eq(senderId), eq("bob"), eq("Hello"), eq(requestId));
    }

    @Test
    void replayedRequestIdReturns200() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, senderId);

        MessageDto dto = message(UUID.randomUUID(), UUID.randomUUID(), senderId);
        given(messageService.sendMessage(eq(senderId), eq("bob"), eq("Hello"), eq(requestId)))
                .willReturn(new SendMessageResult(dto, false));

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","content":"Hello","requestId":"%s"}
                                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(dto.getMessageId().toString()));
    }

    @Test
    void blankContentReturns400() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","content":"","requestId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        then(messageService).shouldHaveNoInteractions();
    }

    @Test
    void missingRequestIdReturns400() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","content":"Hello"}
                                """))
                .andExpect(status().isBadRequest());

        then(messageService).shouldHaveNoInteractions();
    }

    @Test
    void nonFriendReturns403() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, senderId);

        given(messageService.sendMessage(eq(senderId), eq("mallory"), eq("Hello"), eq(requestId)))
                .willThrow(new ForbiddenOperationException());

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"mallory","content":"Hello","requestId":"%s"}
                                """.formatted(requestId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRecipientReturns404() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, senderId);

        given(messageService.sendMessage(eq(senderId), eq("ghost"), eq("Hello"), eq(requestId)))
                .willThrow(new UserNotFoundException("ghost"));

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ghost","content":"Hello","requestId":"%s"}
                                """.formatted(requestId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void conflictingRequestIdReturns409() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, senderId);

        given(messageService.sendMessage(eq(senderId), eq("bob"), eq("Hello"), eq(requestId)))
                .willThrow(new MessageConflictException("Request ID already used"));

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","content":"Hello","requestId":"%s"}
                                """.formatted(requestId)))
                .andExpect(status().isConflict());
    }
}
