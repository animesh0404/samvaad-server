package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.event.LoginBlockedDueToSessionLimitEvent;
import com.samvaad.samvaad_server.auth.exception.BadCredentialsException;
import com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException;
import com.samvaad.samvaad_server.auth.exception.SessionLimitExceededException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionService;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.UserRepo;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthenticationService {

    private final UserRepo userRepo;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final TokenService tokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public AuthenticationService(
            UserRepo userRepo,
            PasswordEncoder passwordEncoder,
            SessionService sessionService,
            TokenService tokenService,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.tokenService = tokenService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    public LoginResponseDto login(LoginRequestDto request, String ipAddress, String userAgent) {
        // Step 1: User lookup (OUTSIDE transaction, read-only)
        User user = userRepo.findByIdentifier(request.getIdentifier().trim())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (user.getPasswordHash() == null) {
            throw new BadCredentialsException("Invalid credentials");
        }

        // Step 2: BCrypt verification (OUTSIDE transaction and OUTSIDE database lock)
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IncorrectPasswordException("Incorrect password");
        }

        // Step 3: Enter short atomic transaction
        LoginResult result = transactionTemplate.execute(status -> {
            // Lock User row with PESSIMISTIC_WRITE
            User lockedUser = userRepo.findByIdWithLock(user.getUserId())
                    .orElseThrow(() -> new UserNotFoundException(user.getUserId()));

            // Single normalization boundary: blank/whitespace-only becomes null.
            // Non-blank values are persisted unchanged. No synthetic IDs are generated.
            String normalizedInstallationId = normalizeInstallationId(request.getInstallationId());

            // Count active sessions
            long activeSessions = sessionService.countActiveSessions(lockedUser);
            if (activeSessions >= SessionService.MAX_ACTIVE_SESSIONS) {
                eventPublisher.publishEvent(new LoginBlockedDueToSessionLimitEvent(
                        lockedUser.getUserId(),
                        Instant.now(),
                        normalizedInstallationId,
                        request.getClientPlatform(),
                        request.getClientName(),
                        request.getClientVersion(),
                        ipAddress,
                        userAgent
                ));
                throw new SessionLimitExceededException();
            }

            // Generate authentication tokens
            String rawRefreshToken = tokenService.generateRefreshToken();
            String refreshTokenHash = tokenService.hashRefreshToken(rawRefreshToken);
            LocalDateTime refreshTokenExpiresAt = LocalDateTime.now().plusDays(tokenService.getRefreshTokenValidityDays());

            // Persist Session using refresh-token hash
            Session session = sessionService.createSession(
                    lockedUser,
                    refreshTokenHash,
                    refreshTokenExpiresAt,
                    normalizedInstallationId,
                    request.getClientPlatform(),
                    request.getClientName(),
                    request.getClientVersion(),
                    ipAddress,
                    userAgent
            );

            // Generate access token bound to session (if this fails, transaction rolls back)
            String accessToken = tokenService.generateAccessToken(lockedUser, session.getSessionId());

            return new LoginResult(
                    accessToken,
                    rawRefreshToken,
                    tokenService.getAccessTokenValiditySeconds(),
                    session.getSessionId()
            );
        });

        // Step 4: Map LoginResult to LoginResponseDto
        return new LoginResponseDto(
                result.accessToken(),
                result.refreshToken(),
                result.expiresIn(),
                result.sessionId()
        );
    }

    private String normalizeInstallationId(String installationId) {
        if (installationId == null || installationId.isBlank()) {
            return null;
        }
        return installationId;
    }

    private record LoginResult(String accessToken, String refreshToken, long expiresIn, UUID sessionId) {}
}
