package com.samvaad.samvaad_server.messaging;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.samvaad.samvaad_server.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ConversationController(conversationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UUID authenticateAs(UserRole role) {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, role, UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
        return userId;
    }

    private ConversationDto conversationDto(UUID conversationId, UUID otherUserId) {
        ConversationDto dto = new ConversationDto();
        dto.setConversationId(conversationId);
        dto.setOtherParticipantUserId(otherUserId);
        dto.setOtherParticipantUsername("bob");
        dto.setLastSequenceNumber(3L);
        dto.setUpdatedAt(LocalDateTime.now());
        return dto;
    }

    private MessageDto messageDto(UUID conversationId, long sequence) {
        MessageDto dto = new MessageDto();
        dto.setMessageId(UUID.randomUUID());
        dto.setConversationId(conversationId);
        dto.setSenderUserId(UUID.randomUUID());
        dto.setSequenceNumber(sequence);
        dto.setContent("message-" + sequence);
        dto.setServerTimestamp(LocalDateTime.now());
        dto.setRequestId(UUID.randomUUID());
        return dto;
    }

    @Test
    void listConversationsReturns200() throws Exception {
        UUID callerId = authenticateAs(UserRole.USER);
        UUID conversationId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        given(conversationService.listConversations(eq(callerId), eq(20), eq(0)))
                .willReturn(List.of(conversationDto(conversationId, otherUserId)));

        mockMvc.perform(get("/api/conversations/direct")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$[0].otherParticipantUserId").value(otherUserId.toString()))
                .andExpect(jsonPath("$[0].otherParticipantUsername").value("bob"))
                .andExpect(jsonPath("$[0].lastSequenceNumber").value(3));

        then(conversationService).should().listConversations(eq(callerId), eq(20), eq(0));
    }

    @Test
    void listConversationsForwardsPagination() throws Exception {
        UUID callerId = authenticateAs(UserRole.USER);
        given(conversationService.listConversations(eq(callerId), eq(5), eq(10)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/conversations/direct")
                        .param("limit", "5")
                        .param("offset", "10"))
                .andExpect(status().isOk());

        then(conversationService).should().listConversations(eq(callerId), eq(5), eq(10));
    }

    @Test
    void listConversationsInvalidPaginationReturns400() throws Exception {
        UUID callerId = authenticateAs(UserRole.USER);
        given(conversationService.listConversations(eq(callerId), anyInt(), anyInt()))
                .willThrow(new InvalidPaginationException("limit must be between 1 and 100"));

        mockMvc.perform(get("/api/conversations/direct")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getMessagesReturns200() throws Exception {
        UUID callerId = authenticateAs(UserRole.USER);
        UUID conversationId = UUID.randomUUID();
        given(conversationService.getMessages(eq(callerId), eq(conversationId), eq(0L), eq(20)))
                .willReturn(List.of(messageDto(conversationId, 1L), messageDto(conversationId, 2L)));

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$[1].sequenceNumber").value(2))
                .andExpect(jsonPath("$[0].conversationId").value(conversationId.toString()));
    }

    @Test
    void getMessagesForwardsCursorAndLimit() throws Exception {
        UUID callerId = authenticateAs(UserRole.USER);
        UUID conversationId = UUID.randomUUID();
        given(conversationService.getMessages(eq(callerId), eq(conversationId), eq(2L), eq(5)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                        .param("afterSequence", "2")
                        .param("limit", "5"))
                .andExpect(status().isOk());

        then(conversationService).should().getMessages(eq(callerId), eq(conversationId), eq(2L), eq(5));
    }

    @Test
    void getMessagesNonParticipantReturns403() throws Exception {
        UUID callerId = authenticateAs(UserRole.USER);
        UUID conversationId = UUID.randomUUID();
        given(conversationService.getMessages(eq(callerId), eq(conversationId), anyLong(), anyInt()))
                .willThrow(new ForbiddenOperationException());

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId))
                .andExpect(status().isForbidden());
    }

    @Test
    void getMessagesUnknownConversationReturns404() throws Exception {
        UUID callerId = authenticateAs(UserRole.USER);
        UUID conversationId = UUID.randomUUID();
        given(conversationService.getMessages(eq(callerId), eq(conversationId), anyLong(), anyInt()))
                .willThrow(new ConversationNotFoundException(conversationId));

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMessagesInvalidPaginationReturns400() throws Exception {
        UUID callerId = authenticateAs(UserRole.USER);
        UUID conversationId = UUID.randomUUID();
        given(conversationService.getMessages(eq(callerId), eq(conversationId), anyLong(), anyInt()))
                .willThrow(new InvalidPaginationException("limit must be between 1 and 100"));

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());
    }
}
