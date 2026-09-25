package com.samvaad.samvaad_server.e2ee.recovery;

import com.samvaad.samvaad_server.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface E2eeRecoveryCodeRepo extends JpaRepository<E2eeRecoveryCode, UUID> {

    /**
     * All currently usable codes for the user: unconsumed and not superseded
     * by a rotation. The current set is exactly this collection. Plain read;
     * atomic consumption locks the matched row (see
     * {@link #findByIdWithLock}) so concurrent uses resolve to one winner.
     */
    @Query("""
        SELECT c FROM E2eeRecoveryCode c
        WHERE c.user = :user AND c.consumedAt IS NULL AND c.supersededAt IS NULL
        ORDER BY c.codePosition ASC
    """)
    List<E2eeRecoveryCode> findUsableByUser(@Param("user") User user);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM E2eeRecoveryCode c WHERE c.recoveryCodeId = :recoveryCodeId")
    Optional<E2eeRecoveryCode> findByIdWithLock(@Param("recoveryCodeId") UUID recoveryCodeId);

    long countByUserAndConsumedAtIsNullAndSupersededAtIsNull(User user);

    boolean existsByUser(User user);

    /**
     * Retires every still-usable code when a fresh set is generated. Prior
     * codes can never be used again after rotation.
     */
    @Modifying
    @Query("""
        UPDATE E2eeRecoveryCode c
        SET c.supersededAt = :supersededAt
        WHERE c.user = :user AND c.consumedAt IS NULL AND c.supersededAt IS NULL
    """)
    int supersedeUsableByUser(@Param("user") User user, @Param("supersededAt") LocalDateTime supersededAt);

    @Modifying
    @Query("DELETE FROM E2eeRecoveryCode c WHERE c.user.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
