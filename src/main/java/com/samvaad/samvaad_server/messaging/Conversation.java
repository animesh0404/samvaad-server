package com.samvaad.samvaad_server.messaging;

import java.util.UUID;

import com.samvaad.samvaad_server.audit.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversations")
public class Conversation extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "participant_a", nullable = false)
    private UUID participantA;

    @Column(name = "participant_b", nullable = false)
    private UUID participantB;

    @Column(name = "last_sequence_number", nullable = false)
    private long lastSequenceNumber;

    public Conversation() {
    }

    public static Conversation between(UUID firstUserId, UUID secondUserId) {
        Conversation conversation = new Conversation();
        if (firstUserId.compareTo(secondUserId) <= 0) {
            conversation.setParticipantA(firstUserId);
            conversation.setParticipantB(secondUserId);
        } else {
            conversation.setParticipantA(secondUserId);
            conversation.setParticipantB(firstUserId);
        }
        conversation.setLastSequenceNumber(0L);
        return conversation;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public UUID getParticipantA() {
        return participantA;
    }

    public void setParticipantA(UUID participantA) {
        this.participantA = participantA;
    }

    public UUID getParticipantB() {
        return participantB;
    }

    public void setParticipantB(UUID participantB) {
        this.participantB = participantB;
    }

    public long getLastSequenceNumber() {
        return lastSequenceNumber;
    }

    public void setLastSequenceNumber(long lastSequenceNumber) {
        this.lastSequenceNumber = lastSequenceNumber;
    }
}
