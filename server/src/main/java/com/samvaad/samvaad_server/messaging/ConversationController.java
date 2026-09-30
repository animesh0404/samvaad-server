package com.samvaad.samvaad_server.messaging;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
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
}
