package com.samvaad.samvaad_server.session;

import com.samvaad.samvaad_server.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    public static final int MAX_ACTIVE_SESSIONS = 5;

    private final SessionRepo sessionRepo;

    public SessionService(SessionRepo sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    public long countActiveSessions(User user) {
        return sessionRepo.countByUserAndRevokedAtIsNullAndRefreshTokenExpiresAtAfter(
                user,
                LocalDateTime.now()
        );
    }

    /**
     * Creates a session. {@code installationId} is optional client/device metadata
     * and may be null. Blank handling is done by the caller; this method persists
     * the value unchanged.
     */
    public Session createSession(
            User user,
            String refreshTokenHash,
            LocalDateTime refreshTokenExpiresAt,
            String installationId,
            ClientPlatform clientPlatform,
            String clientName,
            String clientVersion,
            String ipAddress,
            String userAgent) {

        Session session = new Session();
        session.setUser(user);
        session.setRefreshTokenHash(refreshTokenHash);
        session.setRefreshTokenExpiresAt(refreshTokenExpiresAt);
        session.setInstallationId(installationId);
        session.setClientPlatform(clientPlatform);
        session.setClientName(clientName);
        session.setClientVersion(clientVersion);
        session.setLastSeenIp(ipAddress);
        session.setLastSeenUserAgent(userAgent);
        session.setLastAuthenticatedAt(LocalDateTime.now());

        return sessionRepo.save(session);
    }

    @Transactional
    public void revokeSession(UUID sessionId, RevocationReason reason) {
        Optional<Session> session = sessionRepo.findById(sessionId);
        if (session.isPresent() && session.get().getRevokedAt() == null) {
            Session s = session.get();
            s.setRevokedAt(LocalDateTime.now());
            s.setRevocationReason(reason);
            sessionRepo.save(s);
            UUID revokedUserId = s.getUser() != null ? s.getUser().getUserId() : null;
            log.info("Session revoked sessionId={} userId={} reason={}",
                    sessionId, revokedUserId, reason);
        } else {
            log.debug("Session revoke no-op sessionId={}", sessionId);
        }
    }
}
