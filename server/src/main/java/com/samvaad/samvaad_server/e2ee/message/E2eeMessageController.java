package com.samvaad.samvaad_server.e2ee.message;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.samvaad.samvaad_server.e2ee.dto.AckMailboxDto;
import com.samvaad.samvaad_server.e2ee.dto.AckMailboxResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.AdvanceCursorDto;
import com.samvaad.samvaad_server.e2ee.dto.E2eeCiphertextItemDto;
import com.samvaad.samvaad_server.e2ee.dto.SubmitE2eeMessageDto;
import com.samvaad.samvaad_server.e2ee.dto.SubmitE2eeMessageResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.SyncCursorDto;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Ciphertext transport: batched submission, per-device mailbox, durable
 * history, and sync cursors. The device that acts is always derived from
 * the authenticated session binding — never from caller-supplied ids.
 */
@RestController
@RequestMapping("/api/e2ee")
public class E2eeMessageController {

    private final E2eeMessageService messageService;

    public E2eeMessageController(E2eeMessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/messages")
    public ResponseEntity<SubmitE2eeMessageResponseDto> submitMessage(
            @Valid @RequestBody SubmitE2eeMessageDto request) {
        AuthenticatedUser caller = CurrentUser.require();
        SubmitE2eeMessageResponseDto response =
                messageService.submitMessage(caller.userId(), caller.sessionId(), request);
        if (response.isCreatedNew()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/mailbox")
    public List<E2eeCiphertextItemDto> fetchMailbox(
            @RequestParam(defaultValue = "50") int limit) {
        AuthenticatedUser caller = CurrentUser.require();
        return messageService.fetchMailbox(caller.userId(), caller.sessionId(), limit);
    }

    @PostMapping("/mailbox/ack")
    public AckMailboxResponseDto acknowledge(@Valid @RequestBody AckMailboxDto request) {
        AuthenticatedUser caller = CurrentUser.require();
        return messageService.acknowledge(caller.userId(), caller.sessionId(), request.getMessageIds());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<E2eeCiphertextItemDto> fetchHistory(
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "20") int limit) {
        AuthenticatedUser caller = CurrentUser.require();
        return messageService.fetchHistory(
                caller.userId(), caller.sessionId(), conversationId, afterSequence, limit);
    }

    @PutMapping("/sync")
    public SyncCursorDto advanceCursor(@Valid @RequestBody AdvanceCursorDto request) {
        AuthenticatedUser caller = CurrentUser.require();
        return messageService.advanceCursor(caller.userId(), caller.sessionId(), request);
    }

    @GetMapping("/sync")
    public SyncCursorDto readCursor(@RequestParam UUID conversationId) {
        AuthenticatedUser caller = CurrentUser.require();
        return messageService.readCursor(caller.userId(), caller.sessionId(), conversationId);
    }
}
