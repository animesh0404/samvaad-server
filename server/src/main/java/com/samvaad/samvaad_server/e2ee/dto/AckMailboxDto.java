package com.samvaad.samvaad_server.e2ee.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public class AckMailboxDto {

    @NotNull(message = "Message IDs are required")
    private List<UUID> messageIds;

    public AckMailboxDto() {
    }

    public List<UUID> getMessageIds() {
        return messageIds;
    }

    public void setMessageIds(List<UUID> messageIds) {
        this.messageIds = messageIds;
    }
}
