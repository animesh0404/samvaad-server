package com.samvaad.samvaad_server.messaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepo extends JpaRepository<Message, UUID> {

    Optional<Message> findByRequestId(UUID requestId);

    List<Message> findByConversationConversationIdAndSequenceNumberGreaterThan(
            UUID conversationId, long afterSequence, Pageable pageable);

    @Modifying
    @Query("DELETE FROM Message m WHERE m.sender.userId = :userId")
    void deleteBySenderUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversation.conversationId = :conversationId")
    void deleteByConversationConversationId(@Param("conversationId") UUID conversationId);
}
