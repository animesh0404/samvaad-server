package com.samvaad.samvaad_server.e2ee.message;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface E2eeSyncCursorRepo extends JpaRepository<E2eeSyncCursor, UUID> {

    Optional<E2eeSyncCursor> findByRecipientDeviceIdAndConversationConversationId(
            UUID recipientDeviceId, UUID conversationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM E2eeSyncCursor s WHERE s.recipientDeviceId = :deviceId "
            + "AND s.conversation.conversationId = :conversationId")
    Optional<E2eeSyncCursor> findLockedByDeviceAndConversation(
            @Param("deviceId") UUID deviceId, @Param("conversationId") UUID conversationId);

    @Modifying
    @Query("DELETE FROM E2eeSyncCursor s WHERE s.conversation.conversationId IN "
            + "(SELECT c.conversationId FROM Conversation c "
            + "WHERE c.participantA = :userId OR c.participantB = :userId)")
    void deleteByParticipantUserId(@Param("userId") UUID userId);
}
