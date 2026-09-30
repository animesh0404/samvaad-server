package com.samvaad.samvaad_server.e2ee.device;

import com.samvaad.samvaad_server.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface E2eeDeviceRepo extends JpaRepository<E2eeDevice, UUID> {

    long countByUserAndStatusIn(User user, Collection<DeviceStatus> statuses);

    long countByUserAndStatusInAndDeviceRole(
            User user, Collection<DeviceStatus> statuses, DeviceRole deviceRole);

    boolean existsByUserUserIdAndDeviceRoleAndStatusIn(
            UUID userId, DeviceRole deviceRole, Collection<DeviceStatus> statuses);

    List<E2eeDevice> findByUserUserIdOrderByCreatedAtAsc(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM E2eeDevice d WHERE d.deviceId = :deviceId")
    Optional<E2eeDevice> findByDeviceIdWithLock(@Param("deviceId") UUID deviceId);

    boolean existsByDeviceIdentityPublicKey(byte[] deviceIdentityPublicKey);

    boolean existsByKyberPrekey(byte[] kyberPrekey);

    /**
     * Highest Signal integer id allocated to the account, over all rows
     * including revoked ones so ids are never reused. Must be called
     * within the per-user enrollment lock held by the caller.
     */
    @Query("SELECT COALESCE(MAX(d.signalDeviceId), 0) FROM E2eeDevice d WHERE d.user.userId = :userId")
    int findMaxSignalDeviceIdByUserId(@Param("userId") UUID userId);

    boolean existsByUserUserIdAndSignalDeviceId(UUID userId, int signalDeviceId);

    @Modifying
    @Query("DELETE FROM E2eeDevice d WHERE d.user.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
