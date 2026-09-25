package com.samvaad.samvaad_server.e2ee.device;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface E2eeOneTimePrekeyRepo extends JpaRepository<E2eeOneTimePrekey, UUID> {

    long countByDeviceAndConsumedAtIsNull(E2eeDevice device);

    /**
     * Returns the lowest available one-time prekey under a write lock. The
     * caller consumes it by setting {@code consumedAt} atomically in the same
     * transaction so the same prekey can never be served twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT p FROM E2eeOneTimePrekey p
        WHERE p.device = :device AND p.consumedAt IS NULL
        ORDER BY p.prekeyId ASC
    """)
    List<E2eeOneTimePrekey> findAvailableForUpdate(
            @Param("device") E2eeDevice device, org.springframework.data.domain.Pageable pageable);

    default Optional<E2eeOneTimePrekey> findFirstAvailableForUpdate(E2eeDevice device) {
        List<E2eeOneTimePrekey> available = findAvailableForUpdate(
                device, org.springframework.data.domain.PageRequest.of(0, 1));
        return available.isEmpty() ? Optional.empty() : Optional.of(available.get(0));
    }

    Optional<E2eeOneTimePrekey> findByDeviceAndConsumedByRequestId(
            E2eeDevice device, UUID consumedByRequestId);

    @Modifying
    @Query("DELETE FROM E2eeOneTimePrekey p WHERE p.device.deviceId = :deviceId")
    void deleteByDeviceId(@Param("deviceId") UUID deviceId);

    @Modifying
    @Query("DELETE FROM E2eeOneTimePrekey p WHERE p.device.user.userId = :userId")
    void deleteByDeviceUserId(@Param("userId") UUID userId);
}
