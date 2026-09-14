package com.samvaad.samvaad_server.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.security.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/conversations/direct/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    public ResponseEntity<MessageDto> sendMessage(@Valid @RequestBody SendMessageDto request) {
        AuthenticatedUser caller = CurrentUser.require();
        SendMessageResult result = messageService.sendMessage(
                caller.userId(), request.getUsername(), request.getContent(), request.getRequestId());

        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result.message());
        }
        return ResponseEntity.ok(result.message());
    }
}
