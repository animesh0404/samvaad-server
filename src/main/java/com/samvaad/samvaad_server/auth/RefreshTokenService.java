package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RefreshTokenService {

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
                .orElseThrow(InvalidRefreshTokenException::new);

        if (session.getRevokedAt() != null) {
            throw new InvalidRefreshTokenException();
        }

        if (!session.getRefreshTokenExpiresAt().isAfter(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException();
        }

        String newRawRefreshToken = tokenService.generateRefreshToken();
        String newRefreshTokenHash = tokenService.hashRefreshToken(newRawRefreshToken);
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(tokenService.getRefreshTokenValidityDays());

        session.setRefreshTokenHash(newRefreshTokenHash);
        session.setRefreshTokenExpiresAt(newExpiry);
        sessionRepo.save(session);

        String accessToken = tokenService.generateAccessToken(session.getUser(), session.getSessionId());

        return new LoginResponseDto(
                accessToken,
                newRawRefreshToken,
                tokenService.getAccessTokenValiditySeconds(),
                session.getSessionId()
        );
    }
}