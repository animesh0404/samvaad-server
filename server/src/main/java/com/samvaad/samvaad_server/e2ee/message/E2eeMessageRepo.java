package com.samvaad.samvaad_server.e2ee.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface E2eeMessageRepo extends JpaRepository<E2eeMessage, UUID> {

    Optional<E2eeMessage> findByRequestId(UUID requestId);

    @Modifying
    @Query("DELETE FROM E2eeMessage m WHERE m.conversation.conversationId IN "
            + "(SELECT c.conversationId FROM Conversation c "
            + "WHERE c.participantA = :userId OR c.participantB = :userId)")
    void deleteByParticipantUserId(@Param("userId") UUID userId);

    List<E2eeMessage> findByConversationConversationId(UUID conversationId);
}
