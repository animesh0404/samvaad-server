package com.samvaad.samvaad_server.e2ee.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface E2eeEnvelopeRepo extends JpaRepository<E2eeEnvelope, UUID> {

    /**
     * One device's durable ciphertext stream for a conversation, in
     * authoritative sequence order. History reads — never mailbox state.
     */
    List<E2eeEnvelope> findByMessageConversationConversationIdAndRecipientDeviceIdAndMessageSequenceNumberGreaterThanOrderByMessageSequenceNumberAsc(
            UUID conversationId, UUID recipientDeviceId, long afterSequence, Pageable pageable);

    List<E2eeEnvelope> findByMessageRequestId(UUID requestId);

    List<E2eeEnvelope> findByRecipientDeviceIdAndMessageMessageIdIn(
            UUID recipientDeviceId, Collection<UUID> messageIds);

    @Modifying
    @Query("DELETE FROM E2eeEnvelope e WHERE e.message.conversation.conversationId IN "
            + "(SELECT c.conversationId FROM Conversation c "
            + "WHERE c.participantA = :userId OR c.participantB = :userId)")
    void deleteByParticipantUserId(@Param("userId") UUID userId);
}
