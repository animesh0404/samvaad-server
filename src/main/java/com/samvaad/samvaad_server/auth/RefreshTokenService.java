package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final SessionRepo sessionRepo;
    private final TokenService tokenService;

    public RefreshTokenService(SessionRepo sessionRepo, TokenService tokenService) {
        this.sessionRepo = sessionRepo;
        this.tokenService = tokenService;
    }

    @Transactional
    public LoginResponseDto refresh(String rawRefreshToken) {
        String presentedHash = tokenService.hashRefreshToken(rawRefreshToken);

        Session session = sessionRepo.findByRefreshTokenHash(presentedHash)
                .orElseThrow(() -> {
                    log.warn("Refresh failed: unknown token");
                    return new InvalidRefreshTokenException();
                });

        if (session.getRevokedAt() != null) {
            log.warn("Refresh failed: revoked session sessionId={} userId={}",
                    session.getSessionId(), session.getUser().getUserId());
            throw new InvalidRefreshTokenException();
        }

        if (!session.getRefreshTokenExpiresAt().isAfter(LocalDateTime.now())) {
            log.warn("Refresh failed: expired session sessionId={} userId={}",
                    session.getSessionId(), session.getUser().getUserId());
            throw new InvalidRefreshTokenException();
        }

        String newRawRefreshToken = tokenService.generateRefreshToken();
        String newRefreshTokenHash = tokenService.hashRefreshToken(newRawRefreshToken);
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(tokenService.getRefreshTokenValidityDays());

        session.setRefreshTokenHash(newRefreshTokenHash);
        session.setRefreshTokenExpiresAt(newExpiry);
        sessionRepo.save(session);

        String accessToken = tokenService.generateAccessToken(session.getUser(), session.getSessionId());

        log.info("Refresh succeeded userId={} sessionId={}",
                session.getUser().getUserId(), session.getSessionId());
        return new LoginResponseDto(
                accessToken,
                newRawRefreshToken,
                tokenService.getAccessTokenValiditySeconds(),
                session.getSessionId()
        );
    }
}