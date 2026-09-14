package com.samvaad.samvaad_server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepo conversationRepo;

    @Mock
    private MessageRepo messageRepo;

    @Mock
    private UserRepo userRepo;

    private ConversationService conversationService;

    private User alice;
    private User bob;
    private User carol;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepo, messageRepo, userRepo);

        alice = new User(UUID.randomUUID());
        alice.setUsername("alice");
        alice.setRole(UserRole.USER);

        bob = new User(UUID.randomUUID());
        bob.setUsername("bob");
        bob.setRole(UserRole.USER);

        carol = new User(UUID.randomUUID());
        carol.setUsername("carol");
        carol.setRole(UserRole.USER);
    }

    private Conversation conversationBetween(UUID first, UUID second) {
        Conversation conversation = Conversation.between(first, second);
        conversation.setConversationId(UUID.randomUUID());
        conversation.setLastSequenceNumber(2L);
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private Message message(Conversation conversation, User sender, long sequence) {
        Message message = new Message();
        message.setMessageId(UUID.randomUUID());
        message.setConversation(conversation);
        message.setSender(sender);
        message.setSequenceNumber(sequence);
        message.setContent("message-" + sequence);
        message.setServerTimestamp(LocalDateTime.now());
        message.setRequestId(UUID.randomUUID());
        return message;
    }

    @Test
    void listConversationsResolvesOtherParticipant() {
        Conversation withBob = conversationBetween(alice.getUserId(), bob.getUserId());
        Conversation withCarol = conversationBetween(carol.getUserId(), alice.getUserId());

        given(conversationRepo.findByParticipantAOrParticipantB(
                eq(alice.getUserId()), eq(alice.getUserId()), any(Pageable.class)))
                .willReturn(List.of(withBob, withCarol));
        given(userRepo.findById(bob.getUserId())).willReturn(Optional.of(bob));
        given(userRepo.findById(carol.getUserId())).willReturn(Optional.of(carol));

        List<ConversationDto> result = conversationService.listConversations(alice.getUserId(), 20, 0);

        assertEquals(2, result.size());
        assertEquals(withBob.getConversationId(), result.get(0).getConversationId());
        assertEquals(bob.getUserId(), result.get(0).getOtherParticipantUserId());
        assertEquals("bob", result.get(0).getOtherParticipantUsername());
        assertEquals(carol.getUserId(), result.get(1).getOtherParticipantUserId());
        assertEquals("carol", result.get(1).getOtherParticipantUsername());
        assertEquals(2L, result.get(0).getLastSequenceNumber());
    }

    @Test
    void listConversationsReturnsEmptyWhenNone() {
        given(conversationRepo.findByParticipantAOrParticipantB(
                eq(alice.getUserId()), eq(alice.getUserId()), any(Pageable.class)))
                .willReturn(List.of());

        List<ConversationDto> result = conversationService.listConversations(alice.getUserId(), 20, 0);

        assertTrue(result.isEmpty());
        then(userRepo).shouldHaveNoInteractions();
    }

    @Test
    void listConversationsLeavesUsernameNullWhenUserDeleted() {
        Conversation withBob = conversationBetween(alice.getUserId(), bob.getUserId());

        given(conversationRepo.findByParticipantAOrParticipantB(
                eq(alice.getUserId()), eq(alice.getUserId()), any(Pageable.class)))
                .willReturn(List.of(withBob));
        given(userRepo.findById(bob.getUserId())).willReturn(Optional.empty());

        List<ConversationDto> result = conversationService.listConversations(alice.getUserId(), 20, 0);

        assertEquals(1, result.size());
        assertEquals(bob.getUserId(), result.get(0).getOtherParticipantUserId());
        assertNull(result.get(0).getOtherParticipantUsername());
    }

    @Test
    void listConversationsFetchesExtraPageForNonAlignedOffset() {
        Conversation first = conversationBetween(alice.getUserId(), bob.getUserId());
        Conversation second = conversationBetween(alice.getUserId(), carol.getUserId());
        Conversation third = conversationBetween(alice.getUserId(), bob.getUserId());

        given(conversationRepo.findByParticipantAOrParticipantB(
                eq(alice.getUserId()), eq(alice.getUserId()), any(Pageable.class)))
                .willReturn(List.of(first, second, third));
        given(userRepo.findById(any(UUID.class)))
                .willReturn(Optional.of(bob));

        List<ConversationDto> result = conversationService.listConversations(alice.getUserId(), 2, 3);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(conversationRepo).should().findByParticipantAOrParticipantB(
                eq(alice.getUserId()), eq(alice.getUserId()), pageable.capture());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals(3, pageable.getValue().getPageSize());
        assertEquals(2, result.size());
        assertEquals(second.getConversationId(), result.get(0).getConversationId());
        assertEquals(third.getConversationId(), result.get(1).getConversationId());
    }

    @Test
    void listConversationsRejectsInvalidPagination() {
        assertThrows(InvalidPaginationException.class,
                () -> conversationService.listConversations(alice.getUserId(), 0, 0));
        assertThrows(InvalidPaginationException.class,
                () -> conversationService.listConversations(alice.getUserId(), 101, 0));
        assertThrows(InvalidPaginationException.class,
                () -> conversationService.listConversations(alice.getUserId(), 20, -1));

        then(conversationRepo).shouldHaveNoInteractions();
        then(messageRepo).shouldHaveNoInteractions();
        then(userRepo).shouldHaveNoInteractions();
    }

    @Test
    void getMessagesReturnsDtosInRepositoryOrder() {
        Conversation conversation = conversationBetween(alice.getUserId(), bob.getUserId());
        Message first = message(conversation, alice, 1L);
        Message second = message(conversation, bob, 2L);

        given(conversationRepo.findById(conversation.getConversationId()))
                .willReturn(Optional.of(conversation));
        given(messageRepo.findByConversationConversationIdAndSequenceNumberGreaterThan(
                eq(conversation.getConversationId()), eq(0L), any(Pageable.class)))
                .willReturn(List.of(first, second));

        List<MessageDto> result = conversationService.getMessages(
                alice.getUserId(), conversation.getConversationId(), 0L, 20);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getSequenceNumber());
        assertEquals(2L, result.get(1).getSequenceNumber());
        assertEquals(first.getMessageId(), result.get(0).getMessageId());
        assertEquals(conversation.getConversationId(), result.get(0).getConversationId());
    }

    @Test
    void getMessagesUnknownConversationThrows() {
        UUID unknownId = UUID.randomUUID();
        given(conversationRepo.findById(unknownId)).willReturn(Optional.empty());

        assertThrows(ConversationNotFoundException.class,
                () -> conversationService.getMessages(alice.getUserId(), unknownId, 0L, 20));

        then(messageRepo).shouldHaveNoInteractions();
    }

    @Test
    void getMessagesNonParticipantThrows() {
        Conversation conversation = conversationBetween(alice.getUserId(), bob.getUserId());
        given(conversationRepo.findById(conversation.getConversationId()))
                .willReturn(Optional.of(conversation));

        assertThrows(ForbiddenOperationException.class,
                () -> conversationService.getMessages(carol.getUserId(), conversation.getConversationId(), 0L, 20));

        then(messageRepo).shouldHaveNoInteractions();
    }

    @Test
    void getMessagesRejectsInvalidPagination() {
        assertThrows(InvalidPaginationException.class,
                () -> conversationService.getMessages(alice.getUserId(), UUID.randomUUID(), 0L, 0));
        assertThrows(InvalidPaginationException.class,
                () -> conversationService.getMessages(alice.getUserId(), UUID.randomUUID(), 0L, 101));
        assertThrows(InvalidPaginationException.class,
                () -> conversationService.getMessages(alice.getUserId(), UUID.randomUUID(), -1L, 20));

        then(conversationRepo).shouldHaveNoInteractions();
        then(messageRepo).shouldHaveNoInteractions();
    }

    @Test
    void participantCheckIsTrueForBothParticipants() {
        Conversation conversation = conversationBetween(alice.getUserId(), bob.getUserId());
        given(conversationRepo.findById(conversation.getConversationId()))
                .willReturn(Optional.of(conversation));

        assertTrue(conversationService.isConversationParticipant(
                alice.getUserId(), conversation.getConversationId()));
        assertTrue(conversationService.isConversationParticipant(
                bob.getUserId(), conversation.getConversationId()));
    }

    @Test
    void participantCheckIsFalseForNonParticipantAndUnknownConversation() {
        Conversation conversation = conversationBetween(alice.getUserId(), bob.getUserId());
        given(conversationRepo.findById(conversation.getConversationId()))
                .willReturn(Optional.of(conversation));
        UUID unknownId = UUID.randomUUID();
        given(conversationRepo.findById(unknownId)).willReturn(Optional.empty());

        assertFalse(conversationService.isConversationParticipant(
                carol.getUserId(), conversation.getConversationId()));
        assertFalse(conversationService.isConversationParticipant(alice.getUserId(), unknownId));
    }
}
