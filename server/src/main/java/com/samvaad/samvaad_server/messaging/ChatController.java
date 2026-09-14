package com.samvaad.samvaad_server.messaging;

import java.security.Principal;
import java.util.Map;

import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import com.samvaad.samvaad_server.auth.exception.InvalidAccessTokenException;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.security.AuthenticatedUser;

/**
 * STOMP transport into the existing direct-message business logic.
 * Sender identity always comes from the authenticated STOMP principal;
 * persistence, sequencing, friendship authorization, and idempotency stay
 * in {@link MessageService}. Broadcasts happen only after successful
 * persistence, carrying the server-authoritative {@link MessageDto}.
 */
@Controller
public class ChatController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(MessageService messageService, SimpMessagingTemplate messagingTemplate) {
        this.messageService = messageService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/chat.send")
    public void send(ChatSendRequest request, Principal principal) {
        AuthenticatedUser caller = requireCaller(principal);
        requireValid(request);
        SendMessageResult result = messageService.sendMessageToConversation(
                caller.userId(),
                request.getConversationId(),
                request.getContent(),
                request.getRequestId());
        messagingTemplate.convertAndSend(
                "/topic/conversations/" + request.getConversationId(),
                result.message());
    }

    @MessageExceptionHandler(ForbiddenOperationException.class)
    public Map<String, String> handleForbidden(ForbiddenOperationException ex) {
        return Map.of("message", ex.getMessage());
    }

    @MessageExceptionHandler(ConversationNotFoundException.class)
    public Map<String, String> handleNotFound(ConversationNotFoundException ex) {
        return Map.of("message", ex.getMessage());
    }

    @MessageExceptionHandler(MessageConflictException.class)
    public Map<String, String> handleConflict(MessageConflictException ex) {
        return Map.of("message", ex.getMessage());
    }

    @MessageExceptionHandler({InvalidAccessTokenException.class, IllegalArgumentException.class})
    public Map<String, String> handleBadRequest(RuntimeException ex) {
        return Map.of("message", ex.getMessage());
    }

    private AuthenticatedUser requireCaller(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser caller) {
            return caller;
        }
        throw new InvalidAccessTokenException("Not authenticated");
    }

    private void requireValid(ChatSendRequest request) {
        if (request == null
                || request.getConversationId() == null
                || request.getRequestId() == null
                || request.getContent() == null
                || request.getContent().isBlank()) {
            throw new IllegalArgumentException("conversationId, content, and requestId are required");
        }
    }
}
