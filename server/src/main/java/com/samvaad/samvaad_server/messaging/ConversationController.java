package com.samvaad.samvaad_server.messaging;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.security.CurrentUser;

@RestController
@RequestMapping("/api/conversations/direct")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public List<ConversationDto> listConversations(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        AuthenticatedUser caller = CurrentUser.require();
        return conversationService.listConversations(caller.userId(), limit, offset);
    }

    @GetMapping("/{conversationId}/messages")
    public List<MessageDto> getMessages(
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "20") int limit) {
        AuthenticatedUser caller = CurrentUser.require();
        return conversationService.getMessages(caller.userId(), conversationId, afterSequence, limit);
    }
}
