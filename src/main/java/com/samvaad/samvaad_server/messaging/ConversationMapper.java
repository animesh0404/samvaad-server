package com.samvaad.samvaad_server.messaging;

import java.util.UUID;

public final class ConversationMapper {

    private ConversationMapper() {
    }

    public static ConversationDto toDto(Conversation conversation, UUID otherParticipantUserId,
            String otherParticipantUsername) {
        ConversationDto dto = new ConversationDto();
        dto.setConversationId(conversation.getConversationId());
        dto.setOtherParticipantUserId(otherParticipantUserId);
        dto.setOtherParticipantUsername(otherParticipantUsername);
        dto.setLastSequenceNumber(conversation.getLastSequenceNumber());
        dto.setUpdatedAt(conversation.getUpdatedAt());
        return dto;
    }
}
