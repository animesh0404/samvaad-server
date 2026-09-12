package com.samvaad.samvaad_server.session;

import com.samvaad.samvaad_server.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepo extends JpaRepository<Session, UUID> {

    long countByUserAndRevokedAtIsNullAndRefreshTokenExpiresAtAfter(User user, LocalDateTime now);

    List<Session> findByUserAndRevokedAtIsNullAndRefreshTokenExpiresAtAfter(User user, LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Session s WHERE s.refreshTokenHash = :refreshTokenHash")
    Optional<Session> findByRefreshTokenHash(@Param("refreshTokenHash") String refreshTokenHash);

    @EntityGraph(attributePaths = "user")
    @Query("SELECT s FROM Session s WHERE s.sessionId = :sessionId")
    Optional<Session> findWithUserBySessionId(@Param("sessionId") UUID sessionId);

    @Modifying
    @Query("DELETE FROM Session s WHERE s.user.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
