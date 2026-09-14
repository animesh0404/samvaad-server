package com.samvaad.samvaad_server.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.samvaad.samvaad_server.common.logging.LogCapture;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;

import ch.qos.logback.classic.Level;

@ExtendWith(MockitoExtension.class)
class MessageServiceLoggingTest {

    private static final String SECRET_CONTENT = "top-secret-message-content-abc123";

    @Mock
    private ConversationRepo conversationRepo;

    @Mock
    private MessageRepo messageRepo;

    @Mock
    private UserRepo userRepo;

    @Mock
    private FriendRequestService friendRequestService;

    private MessageService messageService;

    private User alice;
    private User bob;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(conversationRepo, messageRepo, userRepo, friendRequestService);

        alice = new User(UUID.randomUUID());
        alice.setUsername("alice");
        alice.setRole(UserRole.USER);

        bob = new User(UUID.randomUUID());
        bob.setUsername("bob");
        bob.setRole(UserRole.USER);

        conversationId = UUID.randomUUID();
    }

    private void givenFriendsWithNewConversation() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(friendRequestService.areFriends(alice.getUserId(), bob.getUserId())).willReturn(true);
        given(conversationRepo.findLockedByParticipants(any(), any())).willReturn(Optional.empty());
        given(conversationRepo.saveAndFlush(any(Conversation.class))).willAnswer(invocation -> {
            Conversation created = invocation.getArgument(0);
            created.setConversationId(conversationId);
            return created;
        });
        given(messageRepo.findByRequestId(any())).willReturn(Optional.empty());
        given(messageRepo.saveAndFlush(any(Message.class))).willAnswer(invocation -> {
            Message toSave = invocation.getArgument(0);
            toSave.setMessageId(UUID.randomUUID());
            return toSave;
        });
    }

    @Test
    void logsNewMessageWithoutContent() {
        givenFriendsWithNewConversation();
        UUID requestId = UUID.randomUUID();

        try (LogCapture logs = new LogCapture(MessageService.class)) {
            SendMessageResult result = messageService.sendMessage(
                    alice.getUserId(), "bob", SECRET_CONTENT, requestId);

            assertTrue(result.created());
            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains(conversationId.toString())
                    && e.getFormattedMessage().contains(alice.getUserId().toString())
                    && e.getFormattedMessage().contains(requestId.toString())
                    && e.getFormattedMessage().contains("createdNew=true")));
            assertTrue(!logs.text().contains(SECRET_CONTENT), "message content must never be logged");
        }
    }

    @Test
    void logsReplayAtDebugWithoutCreating() {
        UUID requestId = UUID.randomUUID();

        Conversation conversation = new Conversation();
        conversation.setConversationId(conversationId);
        conversation.setParticipantA(alice.getUserId());
        conversation.setParticipantB(bob.getUserId());

        Message existing = new Message();
        existing.setMessageId(UUID.randomUUID());
        existing.setConversation(conversation);
        existing.setSender(alice);
        existing.setSequenceNumber(7L);
        existing.setContent(SECRET_CONTENT);
        existing.setServerTimestamp(LocalDateTime.now());
        existing.setRequestId(requestId);

        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(conversationRepo.findById(conversationId)).willReturn(Optional.of(conversation));
        given(friendRequestService.areFriends(alice.getUserId(), bob.getUserId())).willReturn(true);
        given(messageRepo.findByRequestId(requestId)).willReturn(Optional.of(existing));

        try (LogCapture logs = new LogCapture(MessageService.class)) {
            SendMessageResult result = messageService.sendMessageToConversation(
                    alice.getUserId(), conversationId, SECRET_CONTENT, requestId);

            assertTrue(!result.created());
            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                    && e.getFormattedMessage().contains("createdNew=false")
                    && e.getFormattedMessage().contains(requestId.toString())));
            assertTrue(!logs.text().contains(SECRET_CONTENT), "message content must never be logged");
        }
    }

    @Test
    void logsRequestIdConflictAtWarn() {
        UUID requestId = UUID.randomUUID();
        UUID otherConversationId = UUID.randomUUID();

        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));

        Conversation conversation = new Conversation();
        conversation.setConversationId(conversationId);
        conversation.setParticipantA(alice.getUserId());
        conversation.setParticipantB(bob.getUserId());
        given(conversationRepo.findById(conversationId)).willReturn(Optional.of(conversation));
        given(friendRequestService.areFriends(alice.getUserId(), bob.getUserId())).willReturn(true);

        Conversation otherConversation = new Conversation();
        otherConversation.setConversationId(otherConversationId);
        Message existing = new Message();
        existing.setMessageId(UUID.randomUUID());
        existing.setConversation(otherConversation);
        existing.setSender(alice);
        existing.setSequenceNumber(3L);
        existing.setContent(SECRET_CONTENT);
        existing.setServerTimestamp(LocalDateTime.now());
        existing.setRequestId(requestId);
        given(messageRepo.findByRequestId(requestId)).willReturn(Optional.of(existing));

        try (LogCapture logs = new LogCapture(MessageService.class)) {
            assertThrows(MessageConflictException.class, () -> messageService.sendMessageToConversation(
                    alice.getUserId(), conversationId, SECRET_CONTENT, requestId));

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains(requestId.toString())));
            assertTrue(!logs.text().contains(SECRET_CONTENT), "message content must never be logged");
        }
    }
}
