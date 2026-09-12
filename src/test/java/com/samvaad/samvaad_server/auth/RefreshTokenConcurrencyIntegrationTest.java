package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefreshTokenConcurrencyIntegrationTest {

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    private String rawRefreshToken;
    private UUID initialSessionId;

    @BeforeEach
    void setUp() {
        sessionRepo.deleteAll();
        userProfileRepo.deleteAll();
        userRepo.deleteAll();

        User user = new User();
        user.setUsername("refresh_user");
        user.setEmail("refresh@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        userRepo.save(user);

        LoginResponseDto loginResponse = authenticationService.login(
                new LoginRequestDto(
                        "refresh_user",
                        "secret123",
                        "inst-refresh",
                        ClientPlatform.WEB,
                        "Samvaad Web",
                        "1.0.0"
                ),
                "192.168.1.10",
                "UserAgent"
        );

        rawRefreshToken = loginResponse.refreshToken();
        initialSessionId = loginResponse.sessionId();
    }

    @Test
    void allowsOnlyOneConcurrentRotationOfTheSameRefreshToken() throws Exception {
        int concurrentAttempts = 4;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        CyclicBarrier barrier = new CyclicBarrier(concurrentAttempts);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger rejections = new AtomicInteger(0);
        AtomicReference<LoginResponseDto> winningResponse = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentAttempts; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    LoginResponseDto response = refreshTokenService.refresh(rawRefreshToken);
                    if (response != null && response.refreshToken() != null) {
                        winningResponse.set(response);
                        successes.incrementAndGet();
                    }
                } catch (InvalidRefreshTokenException e) {
                    rejections.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError("Unexpected exception during concurrent refresh", e);
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(1, successes.get(), "Exactly one refresh must succeed");
        assertEquals(concurrentAttempts - 1, rejections.get(), "All other refreshes must be rejected");

        LoginResponseDto winner = winningResponse.get();
        assertNotNull(winner, "The winning response must be captured");
        assertEquals(initialSessionId, winner.sessionId(), "sessionId must be unchanged");

        assertEquals(1L, sessionRepo.count(), "No second session may be created");

        String storedHash = sessionRepo.findById(initialSessionId).orElseThrow().getRefreshTokenHash();
        assertEquals(tokenService.hashRefreshToken(winner.refreshToken()), storedHash,
                "Stored hash must correspond to the winning R2");
        assertNotEquals(tokenService.hashRefreshToken(rawRefreshToken), storedHash,
                "The original R1 hash must no longer be stored");
    }
}