package com.samvaad.samvaad_server.session;

import com.samvaad.samvaad_server.user.User;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SessionService {

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
}
