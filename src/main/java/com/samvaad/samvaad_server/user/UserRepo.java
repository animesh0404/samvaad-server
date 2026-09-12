package com.samvaad.samvaad_server.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepo extends JpaRepository<User, UUID> {

    boolean existsByUsername(String username);

    boolean existsByRole(UserRole role);

    @Query("""
        SELECT u FROM User u
        WHERE u.username = :identifier
           OR (u.email IS NOT NULL AND LOWER(u.email) = LOWER(:identifier))
    """)
    Optional<User> findByIdentifier(@Param("identifier") String identifier);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.userId = :userId")
    Optional<User> findByIdWithLock(@Param("userId") UUID userId);
}