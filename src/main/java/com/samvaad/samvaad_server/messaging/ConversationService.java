package com.samvaad.samvaad_server.messaging;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;

@Service
public class ConversationService {

    static final int MAX_LIMIT = 100;

    private final ConversationRepo conversationRepo;
    private final MessageRepo messageRepo;
    private final UserRepo userRepo;

    public ConversationService(
            ConversationRepo conversationRepo,
            MessageRepo messageRepo,
            UserRepo userRepo) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
        this.userRepo = userRepo;
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> listConversations(UUID callerId, int limit, int offset) {
        validateOffsetPagination(limit, offset);
        int drop = offset % limit;
        PageRequest pageable = PageRequest.of(
                offset / limit,
                limit + drop,
                Sort.by(Sort.Direction.DESC, "updatedAt")
                        .and(Sort.by(Sort.Direction.ASC, "conversationId")));
        return conversationRepo.findByParticipantAOrParticipantB(callerId, callerId, pageable)
                .stream()
                .skip(drop)
                .map(conversation -> toDto(conversation, callerId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getMessages(UUID callerId, UUID conversationId, long afterSequence, int limit) {
        validateLimit(limit);
        if (afterSequence < 0) {
            throw new InvalidPaginationException("afterSequence must be >= 0");
        }
        Conversation conversation = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        if (!conversation.getParticipantA().equals(callerId)
                && !conversation.getParticipantB().equals(callerId)) {
            throw new ForbiddenOperationException();
        }
        PageRequest pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "sequenceNumber"));
        return messageRepo
                .findByConversationConversationIdAndSequenceNumberGreaterThan(conversationId, afterSequence, pageable)
                .stream()
                .map(MessageMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isConversationParticipant(UUID callerId, UUID conversationId) {
        return conversationRepo.findById(conversationId)
                .map(conversation -> conversation.getParticipantA().equals(callerId)
                        || conversation.getParticipantB().equals(callerId))
                .orElse(false);
    }

    private ConversationDto toDto(Conversation conversation, UUID callerId) {
        UUID otherParticipantId = conversation.getParticipantA().equals(callerId)
                ? conversation.getParticipantB()
                : conversation.getParticipantA();
        String otherUsername = userRepo.findById(otherParticipantId)
                .map(User::getUsername)
                .orElse(null);
        return ConversationMapper.toDto(conversation, otherParticipantId, otherUsername);
    }

    private void validateOffsetPagination(int limit, int offset) {
        validateLimit(limit);
        if (offset < 0) {
            throw new InvalidPaginationException("offset must be >= 0");
        }
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidPaginationException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
