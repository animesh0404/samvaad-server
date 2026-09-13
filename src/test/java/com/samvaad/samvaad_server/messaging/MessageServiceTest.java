package com.samvaad.samvaad_server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import static org.mockito.Mockito.never;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

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

    @BeforeEach
    void setUp() {
        messageService = new MessageService(conversationRepo, messageRepo, userRepo, friendRequestService);

        alice = new User(UUID.randomUUID());
        alice.setUsername("alice");
        alice.setRole(UserRole.USER);

        bob = new User(UUID.randomUUID());
        bob.setUsername("bob");
        bob.setRole(UserRole.USER);
    }

    private UUID[] normalizedPair() {
        if (alice.getUserId().compareTo(bob.getUserId()) <= 0) {
            return new UUID[]{alice.getUserId(), bob.getUserId()};
        }
        return new UUID[]{bob.getUserId(), alice.getUserId()};
    }

    private Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setConversationId(UUID.randomUUID());
        if (alice.getUserId().compareTo(bob.getUserId()) <= 0) {
            conversation.setParticipantA(alice.getUserId());
            conversation.setParticipantB(bob.getUserId());
        } else {
            conversation.setParticipantA(bob.getUserId());
            conversation.setParticipantB(alice.getUserId());
        }
        conversation.setLastSequenceNumber(0L);
        return conversation;
    }

    @Test
    void sendsFirstMessageCreatingConversation() {
        UUID requestId = UUID.randomUUID();

        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(conversationRepo.findLockedByParticipants(normalizedPair()[0], normalizedPair()[1]))
                .willReturn(Optional.empty());
        given(conversationRepo.saveAndFlush(any(Conversation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(friendRequestService.areFriends(alice.getUserId(), bob.getUserId())).willReturn(true);
        given(messageRepo.findByRequestId(requestId)).willReturn(Optional.empty());
        given(messageRepo.saveAndFlush(any(Message.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        SendMessageResult result = messageService.sendMessage(alice.getUserId(), "bob", "Hello", requestId);

        assertTrue(result.created());
        assertEquals("Hello", result.message().getContent());
        assertEquals(1L, result.message().getSequenceNumber());
        assertEquals(alice.getUserId(), result.message().getSenderUserId());
        assertEquals(requestId, result.message().getRequestId());
        assertNotNull(result.message().getServerTimestamp());
    }

    @Test
    void sendsMessageInExistingConversationWithNextSequence() {
        Conversation existing = conversation();
        existing.setLastSequenceNumber(5L);
        UUID requestId = UUID.randomUUID();

        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(conversationRepo.findLockedByParticipants(normalizedPair()[0], normalizedPair()[1]))
                .willReturn(Optional.of(existing));
        given(friendRequestService.areFriends(alice.getUserId(), bob.getUserId())).willReturn(true);
        given(messageRepo.findByRequestId(requestId)).willReturn(Optional.empty());
        given(messageRepo.saveAndFlush(any(Message.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        SendMessageResult result = messageService.sendMessage(alice.getUserId(), "bob", "Again", requestId);

        assertTrue(result.created());
        assertEquals(6L, result.message().getSequenceNumber());
        assertEquals(existing.getConversationId(), result.message().getConversationId());
        then(conversationRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void nonFriendCannotSend() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(friendRequestService.areFriends(alice.getUserId(), bob.getUserId())).willReturn(false);

        assertThrows(ForbiddenOperationException.class,
                () -> messageService.sendMessage(alice.getUserId(), "bob", "Hello", UUID.randomUUID()));

        then(conversationRepo).shouldHaveNoInteractions();
        then(messageRepo).shouldHaveNoInteractions();
    }

    @Test
    void cannotMessageSelf() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("alice")).willReturn(Optional.of(alice));

        assertThrows(ForbiddenOperationException.class,
                () -> messageService.sendMessage(alice.getUserId(), "alice", "Hello", UUID.randomUUID()));

        then(friendRequestService).shouldHaveNoInteractions();
        then(conversationRepo).shouldHaveNoInteractions();
        then(messageRepo).shouldHaveNoInteractions();
    }

    @Test
    void unknownRecipientThrows() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("ghost")).willReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> messageService.sendMessage(alice.getUserId(), "ghost", "Hello", UUID.randomUUID()));

        then(messageRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void raceLoserPersistsMessageInWinnerConversation() {
        Conversation winner = conversation();
        winner.setLastSequenceNumber(2L);
        UUID requestId = UUID.randomUUID();

        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(conversationRepo.findLockedByParticipants(normalizedPair()[0], normalizedPair()[1]))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(winner));
        given(conversationRepo.saveAndFlush(any(Conversation.class)))
                .willThrow(new DataIntegrityViolationException("duplicate"));
        given(friendRequestService.areFriends(alice.getUserId(), bob.getUserId())).willReturn(true);
        given(messageRepo.findByRequestId(requestId)).willReturn(Optional.empty());
        given(messageRepo.saveAndFlush(any(Message.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        SendMessageResult result = messageService.sendMessage(alice.getUserId(), "bob", "Hello", requestId);

        assertTrue(result.created());
        assertEquals(winner.getConversationId(), result.message().getConversationId());
        assertEquals(3L, result.message().getSequenceNumber());
    }

    @Test
    void replayedRequestIdReturnsOriginalWithoutDuplicate() {
        Conversation existing = conversation();
        Message original = new Message();
        original.setMessageId(UUID.randomUUID());
        original.setConversation(existing);
        original.setSender(alice);
        original.setSequenceNumber(4L);
        original.setContent("Hello");
        original.setServerTimestamp(java.time.LocalDateTime.now());
        original.setRequestId(UUID.randomUUID());

        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(conversationRepo.findLockedByParticipants(normalizedPair()[0], normalizedPair()[1]))
                .willReturn(Optional.of(existing));
        given(friendRequestService.areFriends(alice.getUserId(), bob.getUserId())).willReturn(true);
        given(messageRepo.findByRequestId(original.getRequestId())).willReturn(Optional.of(original));

        SendMessageResult result = messageService.sendMessage(
                alice.getUserId(), "bob", "Hello", original.getRequestId());

        assertFalse(result.created());
        assertEquals(original.getMessageId(), result.message().getMessageId());
        assertEquals(4L, result.message().getSequenceNumber());
        then(messageRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void requestIdOwnedByAnotherConversationConflicts() {
        Conversation existing = conversation();
        Conversation other = conversation();
        other.setConversationId(UUID.randomUUID());
        Message foreign = new Message();
        foreign.setMessageId(UUID.randomUUID());
        foreign.setConversation(other);
        foreign.setSender(bob);
        foreign.setRequestId(UUID.randomUUID());

        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(conversationRepo.findLockedByParticipants(normalizedPair()[0], normalizedPair()[1]))
                .willReturn(Optional.of(existing));
        given(friendRequestService.areFriends(alice.getUserId(), bob.getUserId())).willReturn(true);
        given(messageRepo.findByRequestId(foreign.getRequestId())).willReturn(Optional.of(foreign));

        assertThrows(MessageConflictException.class,
                () -> messageService.sendMessage(alice.getUserId(), "bob", "Hello", foreign.getRequestId()));

        then(messageRepo).should(never()).saveAndFlush(any());
    }
}
