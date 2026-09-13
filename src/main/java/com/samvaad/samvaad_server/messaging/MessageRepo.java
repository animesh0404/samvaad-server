package com.samvaad.samvaad_server.messaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepo extends JpaRepository<Message, UUID> {

    Optional<Message> findByRequestId(UUID requestId);

    List<Message> findByConversationConversationIdAndSequenceNumberGreaterThan(
            UUID conversationId, long afterSequence, Pageable pageable);
}
