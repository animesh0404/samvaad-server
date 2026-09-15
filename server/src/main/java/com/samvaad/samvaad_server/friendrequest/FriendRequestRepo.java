package com.samvaad.samvaad_server.friendrequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface FriendRequestRepo extends JpaRepository<FriendRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FriendRequest f WHERE f.requestId = :requestId")
    Optional<FriendRequest> findByIdWithLock(@Param("requestId") UUID requestId);

    @EntityGraph(attributePaths = {"sender", "recipient"})
    List<FriendRequest> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(
            UUID recipientUserId, FriendRequestStatus status);

    @EntityGraph(attributePaths = {"sender", "recipient"})
    List<FriendRequest> findBySenderUserIdAndStatusOrderByCreatedAtDesc(
            UUID senderUserId, FriendRequestStatus status);

    @EntityGraph(attributePaths = {"sender", "recipient"})
    @Query("""
        SELECT f FROM FriendRequest f
        WHERE f.status = 'PENDING'
          AND ((f.sender.userId = :firstUserId AND f.recipient.userId = :secondUserId)
            OR (f.sender.userId = :secondUserId AND f.recipient.userId = :firstUserId))
    """)
    List<FriendRequest> findPendingBetween(
            @Param("firstUserId") UUID firstUserId,
            @Param("secondUserId") UUID secondUserId);

    @Query("""
        SELECT COUNT(f) > 0 FROM FriendRequest f
        WHERE f.status = :status
          AND ((f.sender.userId = :firstUserId AND f.recipient.userId = :secondUserId)
            OR (f.sender.userId = :secondUserId AND f.recipient.userId = :firstUserId))
    """)
    boolean existsByStatusBetween(
            @Param("status") FriendRequestStatus status,
            @Param("firstUserId") UUID firstUserId,
            @Param("secondUserId") UUID secondUserId);

    default boolean existsAcceptedBetween(UUID firstUserId, UUID secondUserId) {
        return existsByStatusBetween(FriendRequestStatus.ACCEPTED, firstUserId, secondUserId);
    }

    @EntityGraph(attributePaths = {"sender", "recipient"})
    @Query("""
        SELECT f FROM FriendRequest f
        WHERE f.status = :status
          AND (f.sender.userId = :userId OR f.recipient.userId = :userId)
    """)
    List<FriendRequest> findByStatusInvolving(
            @Param("status") FriendRequestStatus status,
            @Param("userId") UUID userId);

    default List<FriendRequest> findAcceptedInvolving(UUID userId) {
        return findByStatusInvolving(FriendRequestStatus.ACCEPTED, userId);
    }

    @Modifying
    @Query("""
        DELETE FROM FriendRequest f
        WHERE f.sender.userId = :userId OR f.recipient.userId = :userId
    """)
    void deleteByParticipantUserId(@Param("userId") UUID userId);
}
