package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.event.LoginBlockedDueToSessionLimitEvent;
import com.samvaad.samvaad_server.auth.exception.SessionLimitExceededException;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.Session;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class LoginConcurrencyIntegrationTest {

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ApplicationEvents applicationEvents;

    private User testUser;

    @BeforeEach
    void setUp() {
        sessionRepo.deleteAll();
        userProfileRepo.deleteAll();
        userRepo.deleteAll();

        User user = new User();
        user.setUsername("concurrency_user");
        user.setEmail("concurrency@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        testUser = userRepo.save(user);
    }

    @Test
    void serializesConcurrentLoginsAndEnforcesFiveSessionLimit() throws Exception {
        // Pre-create 4 active sessions for the user
        for (int i = 1; i <= 4; i++) {
            Session session = new Session();
            session.setUser(testUser);
            session.setRefreshTokenHash("initial-token-hash-" + i);
            session.setRefreshTokenExpiresAt(LocalDateTime.now().plusDays(30));
            session.setInstallationId("inst-" + i);
            session.setClientPlatform(ClientPlatform.WEB);
            session.setLastAuthenticatedAt(LocalDateTime.now());
            sessionRepo.save(session);
        }

        assertEquals(4, sessionRepo.countByUserAndRevokedAtIsNullAndRefreshTokenExpiresAtAfter(testUser, LocalDateTime.now()));

        // Run 4 concurrent login attempts across threads for the same user
        int concurrentAttempts = 4;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        CyclicBarrier barrier = new CyclicBarrier(concurrentAttempts);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger sessionLimitExceptions = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 1; i <= concurrentAttempts; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                LoginRequestDto request = new LoginRequestDto(
                        "concurrency_user",
                        "secret123",
                        "inst-concurrent-" + index,
                        ClientPlatform.DESKTOP,
                        "Samvaad Desktop",
                        "1.0.0"
                );

                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    LoginResponseDto response = authenticationService.login(request, "192.168.1." + index, "UserAgent");
                    if (response != null && response.sessionId() != null) {
                        successes.incrementAndGet();
                    }
                } catch (SessionLimitExceededException e) {
                    sessionLimitExceptions.incrementAndGet();
                } catch (Exception e) {
                     throw new AssertionError("Unexpected exception during concurrent login", e);
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly ONE concurrent login must succeed to reach the 5-session limit
        assertEquals(1, successes.get(), "Exactly one concurrent login must succeed");
        // Exactly 3 must be rejected due to session limit
        assertEquals(3, sessionLimitExceptions.get(), "Remaining concurrent logins must be rejected");

        // The database must contain exactly 5 active sessions
        long finalActiveCount = sessionRepo.countByUserAndRevokedAtIsNullAndRefreshTokenExpiresAtAfter(
                testUser, LocalDateTime.now()
        );
        assertEquals(5, finalActiveCount, "Active sessions in database must be exactly 5");

        // Verify that events were published for the blocked attempts
        long eventCount = applicationEvents.stream(LoginBlockedDueToSessionLimitEvent.class).count();
        assertEquals(3, eventCount, "Should have published exactly 3 LoginBlockedDueToSessionLimitEvents");
    }
}
