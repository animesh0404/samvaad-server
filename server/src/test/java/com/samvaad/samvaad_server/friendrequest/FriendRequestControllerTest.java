package com.samvaad.samvaad_server.friendrequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.doThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class FriendRequestControllerTest {

    private MockMvc mockMvc;

    @Mock
    private FriendRequestService friendRequestService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new FriendRequestController(friendRequestService))
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

    private FriendRequestDto response(UUID requestId, UUID senderId, UUID recipientId,
            FriendRequestStatus status) {
        FriendRequestDto dto = new FriendRequestDto();
        dto.setRequestId(requestId);
        dto.setSenderUserId(senderId);
        dto.setSenderUsername("alice");
        dto.setRecipientUserId(recipientId);
        dto.setRecipientUsername("bob");
        dto.setStatus(status);
        return dto;
    }

    @Test
    void sendRequestReturns201() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, senderId);

        given(friendRequestService.sendRequest(eq(senderId), eq("bob")))
                .willReturn(response(requestId, senderId, recipientId, FriendRequestStatus.PENDING));

        mockMvc.perform(post("/api/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        then(friendRequestService).should().sendRequest(eq(senderId), eq("bob"));
    }

    @Test
    void sendRequestBlankUsernameReturns400() throws Exception {
        authenticateAs(UserRole.USER, UUID.randomUUID());

        mockMvc.perform(post("/api/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":""}
                                """))
                .andExpect(status().isBadRequest());

        then(friendRequestService).shouldHaveNoInteractions();
    }

    @Test
    void sendRequestUnknownUserReturns404() throws Exception {
        UUID senderId = UUID.randomUUID();
        authenticateAs(UserRole.USER, senderId);

        given(friendRequestService.sendRequest(eq(senderId), eq("ghost")))
                .willThrow(new UserNotFoundException("ghost"));

        mockMvc.perform(post("/api/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ghost"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendDuplicateRequestReturns409() throws Exception {
        UUID senderId = UUID.randomUUID();
        authenticateAs(UserRole.USER, senderId);

        given(friendRequestService.sendRequest(eq(senderId), eq("bob")))
                .willThrow(new FriendRequestConflictException("Friend request already pending"));

        mockMvc.perform(post("/api/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptRequestReturns200() throws Exception {
        UUID recipientId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, recipientId);

        given(friendRequestService.acceptRequest(eq(recipientId), eq(requestId)))
                .willReturn(response(requestId, UUID.randomUUID(), recipientId,
                        FriendRequestStatus.ACCEPTED));

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void rejectRequestReturns200() throws Exception {
        UUID recipientId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, recipientId);

        given(friendRequestService.rejectRequest(eq(recipientId), eq(requestId)))
                .willReturn(response(requestId, UUID.randomUUID(), recipientId,
                        FriendRequestStatus.REJECTED));

        mockMvc.perform(post("/api/friend-requests/{requestId}/reject", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void cancelRequestReturns200() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, senderId);

        given(friendRequestService.cancelRequest(eq(senderId), eq(requestId)))
                .willReturn(response(requestId, senderId, UUID.randomUUID(),
                        FriendRequestStatus.CANCELLED));

        mockMvc.perform(post("/api/friend-requests/{requestId}/cancel", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void wrongPartyCannotMutateRequest() throws Exception {
        UUID callerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, callerId);

        given(friendRequestService.acceptRequest(eq(callerId), eq(requestId)))
                .willThrow(new ForbiddenOperationException());
        given(friendRequestService.cancelRequest(eq(callerId), eq(requestId)))
                .willThrow(new ForbiddenOperationException());

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/friend-requests/{requestId}/cancel", requestId))
                .andExpect(status().isForbidden());
    }

    @Test
    void mutateNonexistentRequestReturns404() throws Exception {
        UUID callerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, callerId);

        given(friendRequestService.acceptRequest(eq(callerId), eq(requestId)))
                .willThrow(new FriendRequestNotFoundException(requestId));

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId))
                .andExpect(status().isNotFound());
    }

    @Test
    void mutateTerminalRequestReturns409() throws Exception {
        UUID callerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.USER, callerId);

        given(friendRequestService.acceptRequest(eq(callerId), eq(requestId)))
                .willThrow(new FriendRequestConflictException("Friend request is no longer pending"));

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId))
                .andExpect(status().isConflict());
    }

    @Test
    void listsIncomingAndOutgoing() throws Exception {
        UUID callerId = UUID.randomUUID();
        authenticateAs(UserRole.USER, callerId);

        given(friendRequestService.listIncoming(eq(callerId))).willReturn(List.of());
        given(friendRequestService.listOutgoing(eq(callerId))).willReturn(List.of());

        mockMvc.perform(get("/api/friend-requests/incoming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/friend-requests/outgoing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void adminUsesSameSelfServiceContract() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        authenticateAs(UserRole.ADMIN, adminId);

        given(friendRequestService.acceptRequest(eq(adminId), eq(requestId)))
                .willThrow(new ForbiddenOperationException());

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId))
                .andExpect(status().isForbidden());
    }
}
