package com.samvaad.samvaad_server.messaging;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.UserRepo;

@Service
public class MessageService {

    private final ConversationRepo conversationRepo;
    private final MessageRepo messageRepo;
    private final UserRepo userRepo;
    private final FriendRequestService friendRequestService;

    public MessageService(
            ConversationRepo conversationRepo,
            MessageRepo messageRepo,
            UserRepo userRepo,
            FriendRequestService friendRequestService) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
        this.userRepo = userRepo;
        this.friendRequestService = friendRequestService;
    }

    @Transactional
    public SendMessageResult sendMessage(UUID senderId, String username, String content, UUID requestId) {
        User sender = userRepo.findById(senderId)
                .orElseThrow(() -> new UserNotFoundException(senderId));

        User recipient = userRepo.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new UserNotFoundException(username));

        if (recipient.getUserId().equals(senderId)) {
            throw new ForbiddenOperationException();
        }

        if (!friendRequestService.areFriends(senderId, recipient.getUserId())) {
            throw new ForbiddenOperationException();
        }

        Conversation conversation = findOrCreateConversation(senderId, recipient.getUserId());

        return persistMessage(sender, conversation, content, requestId);
    }

    @Transactional
    public SendMessageResult sendMessageToConversation(
            UUID senderId, UUID conversationId, String content, UUID requestId) {
        User sender = userRepo.findById(senderId)
                .orElseThrow(() -> new UserNotFoundException(senderId));

        Conversation conversation = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        if (!conversation.getParticipantA().equals(senderId)
                && !conversation.getParticipantB().equals(senderId)) {
            throw new ForbiddenOperationException();
        }

        UUID otherParticipantId = conversation.getParticipantA().equals(senderId)
                ? conversation.getParticipantB()
                : conversation.getParticipantA();
        if (!friendRequestService.areFriends(senderId, otherParticipantId)) {
            throw new ForbiddenOperationException();
        }

        return persistMessage(sender, conversation, content, requestId);
    }

    private SendMessageResult persistMessage(
            User sender, Conversation conversation, String content, UUID requestId) {
        Optional<Message> replay = messageRepo.findByRequestId(requestId);
        if (replay.isPresent()) {
            Message existing = replay.get();
            if (!existing.getConversation().getConversationId().equals(conversation.getConversationId())
                    || !existing.getSender().getUserId().equals(sender.getUserId())) {
                throw new MessageConflictException("Request ID already used");
            }
            return new SendMessageResult(MessageMapper.toDto(existing), false);
        }

        conversation.setLastSequenceNumber(conversation.getLastSequenceNumber() + 1);

        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(sender);
        message.setSequenceNumber(conversation.getLastSequenceNumber());
        message.setContent(content);
        message.setServerTimestamp(LocalDateTime.now());
        message.setRequestId(requestId);

        try {
            Message saved = messageRepo.saveAndFlush(message);
            return new SendMessageResult(MessageMapper.toDto(saved), true);
        } catch (DataIntegrityViolationException e) {
            throw new MessageConflictException("Request ID already used");
        }
    }

    private Conversation findOrCreateConversation(UUID senderId, UUID recipientId) {
        UUID participantA = senderId.compareTo(recipientId) <= 0 ? senderId : recipientId;
        UUID participantB = senderId.compareTo(recipientId) <= 0 ? recipientId : senderId;

        Optional<Conversation> existing = conversationRepo.findLockedByParticipants(participantA, participantB);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return conversationRepo.saveAndFlush(Conversation.between(senderId, recipientId));
        } catch (DataIntegrityViolationException e) {
            return conversationRepo.findLockedByParticipants(participantA, participantB)
                    .orElseThrow(() -> e);
        }
    }
}
