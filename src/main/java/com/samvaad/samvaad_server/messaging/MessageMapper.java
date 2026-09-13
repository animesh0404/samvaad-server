package com.samvaad.samvaad_server.messaging;

public final class MessageMapper {

    private MessageMapper() {
    }

    public static MessageDto toDto(Message message) {
        MessageDto dto = new MessageDto();
        dto.setMessageId(message.getMessageId());
        dto.setConversationId(message.getConversation().getConversationId());
        dto.setSenderUserId(message.getSender().getUserId());
        dto.setSequenceNumber(message.getSequenceNumber());
        dto.setContent(message.getContent());
        dto.setServerTimestamp(message.getServerTimestamp());
        dto.setRequestId(message.getRequestId());
        return dto;
    }
}
