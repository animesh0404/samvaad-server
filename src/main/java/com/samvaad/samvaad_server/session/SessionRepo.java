package com.samvaad.samvaad_server.session;

import com.samvaad.samvaad_server.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface SessionRepo extends JpaRepository<Session, UUID> {

    long countByUserAndRevokedAtIsNullAndRefreshTokenExpiresAtAfter(User user, LocalDateTime now);

    List<Session> findByUserAndRevokedAtIsNullAndRefreshTokenExpiresAtAfter(User user, LocalDateTime now);
}
