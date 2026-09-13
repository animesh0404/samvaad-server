package com.samvaad.samvaad_server.messaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ConversationRepo extends JpaRepository<Conversation, UUID> {

    List<Conversation> findByParticipantAOrParticipantB(UUID participantA, UUID participantB, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT c FROM Conversation c
        WHERE c.participantA = :participantA AND c.participantB = :participantB
    """)
    Optional<Conversation> findLockedByParticipants(
            @Param("participantA") UUID participantA,
            @Param("participantB") UUID participantB);
}
