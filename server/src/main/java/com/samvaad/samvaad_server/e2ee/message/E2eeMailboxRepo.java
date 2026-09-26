package com.samvaad.samvaad_server.e2ee.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface E2eeMailboxRepo extends JpaRepository<E2eeMailboxEntry, UUID> {

    /**
     * One device's undelivered queue in stable acceptance order. Repeated
     * fetches before acknowledgement return identical rows.
     */
    List<E2eeMailboxEntry> findByRecipientDeviceIdOrderByMessageServerTimestampAscMessageMessageIdAsc(
            UUID recipientDeviceId, Pageable pageable);

    long countByRecipientDeviceId(UUID recipientDeviceId);

    /**
     * Scoped delete: only entries of the caller's own device can be
     * acknowledged, so a guessed foreign message id acknowledges nothing.
     */
    @Modifying
    @Query("DELETE FROM E2eeMailboxEntry m WHERE m.recipientDeviceId = :deviceId "
            + "AND m.message.messageId IN :messageIds")
    int deleteByDeviceAndMessageIds(
            @Param("deviceId") UUID deviceId, @Param("messageIds") Collection<UUID> messageIds);

    @Modifying
    @Query("DELETE FROM E2eeMailboxEntry m WHERE m.message.conversation.conversationId IN "
            + "(SELECT c.conversationId FROM Conversation c "
            + "WHERE c.participantA = :userId OR c.participantB = :userId)")
    void deleteByParticipantUserId(@Param("userId") UUID userId);
}
