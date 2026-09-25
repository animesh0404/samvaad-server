package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.e2ee.device.DeviceStatus;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo;
import com.samvaad.samvaad_server.e2ee.dto.ClaimPrekeyResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.RecoveryEnrollRequestDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceLimitExceededException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidRecoveryCodeException;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * Enrollment cap, prekey single-consumption, and recovery single-use under
 * concurrency. Follows the {@code LoginConcurrencyIntegrationTest} barrier
 * pattern.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class E2eeConcurrencyIntegrationTest {

    @Autowired
    private E2eeDeviceService deviceService;

    @Autowired
    private com.samvaad.samvaad_server.friendrequest.FriendRequestService friendRequestService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserService userService;

    @Autowired
    private E2eeDeviceRepo deviceRepo;

    @Autowired
    private E2eeOneTimePrekeyRepo prekeyRepo;

    @Autowired
    private E2eeRecoveryCodeRepo recoveryCodeRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private com.samvaad.samvaad_server.messaging.MessageRepo messageRepo;

    @Autowired
    private com.samvaad.samvaad_server.messaging.ConversationRepo conversationRepo;

    @Autowired
    private com.samvaad.samvaad_server.friendrequest.FriendRequestRepo friendRequestRepo;

    @BeforeEach
    void setUp() {
        prekeyRepo.deleteAll();
        recoveryCodeRepo.deleteAll();
        messageRepo.deleteAll();
        conversationRepo.deleteAll();
        friendRequestRepo.deleteAll();
        sessionRepo.deleteAll();
        deviceRepo.deleteAll();
        userProfileRepo.deleteAll();
        userRepo.deleteAll();
    }

    private User createUser(String username) {
        return userRepo.findById(userService
                .createUser(new CreateUserRequestDto(username, "secret123", username + "@example.com"))
                .getUserId()).orElseThrow();
    }

    private LoginResponseDto login(String username) {
        return authenticationService.login(
                new LoginRequestDto(username, "secret123", null, ClientPlatform.WEB, "Test", "1.0.0"),
                "127.0.0.1",
                "UserAgent");
    }

    @Test
    void serializesConcurrentEnrollmentsAndEnforcesFiveDeviceLimit() throws Exception {
        User user = createUser("e2ee_conc_cap");
        LoginResponseDto bootstrapLogin = login(user.getUsername());
        deviceService.enrollDevice(
                user.getUserId(), bootstrapLogin.sessionId(), E2eeTestKeys.enrollRequest(300, ClientPlatform.WEB));

        int concurrentAttempts = 6;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        CyclicBarrier barrier = new CyclicBarrier(concurrentAttempts);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger limitExceeded = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 1; i <= concurrentAttempts; i++) {
            final int seed = 300 + i;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                    deviceService.enrollDevice(user.getUserId(), bootstrapLogin.sessionId(),
                            E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
                    successes.incrementAndGet();
                } catch (DeviceLimitExceededException e) {
                    limitExceeded.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError("Unexpected exception during concurrent enrollment", e);
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // One bootstrap exists; exactly four of six concurrent enrollments may succeed.
        assertEquals(4, successes.get(), "Exactly four concurrent enrollments must succeed");
        assertEquals(2, limitExceeded.get(), "Remaining enrollments must hit the device limit");
        long enrolled = deviceRepo.countByUserAndStatusIn(user,
                java.util.EnumSet.of(DeviceStatus.PENDING, DeviceStatus.ACTIVE));
        assertEquals(5, enrolled, "Database must hold exactly five non-revoked devices");
    }

    @Test
    void concurrentClaimsOnLastPrekeyResolveToOneWinner() throws Exception {
        User alice = createUser("e2ee_conc_claim");
        User bob = createUser("e2ee_conc_claim_bob");
        com.samvaad.samvaad_server.friendrequest.FriendRequestDto request =
                friendRequestService.sendRequest(alice.getUserId(), bob.getUsername());
        friendRequestService.acceptRequest(bob.getUserId(), request.getRequestId());

        LoginResponseDto aliceLogin = login(alice.getUsername());
        UUID aliceDevice = deviceService.enrollDevice(
                alice.getUserId(), aliceLogin.sessionId(), E2eeTestKeys.enrollRequest(320, ClientPlatform.WEB))
                .getDevice().getDeviceId();
        deviceService.uploadOneTimePrekeys(
                alice.getUserId(), aliceLogin.sessionId(), aliceDevice, E2eeTestKeys.uploadBatch(1));
        for (int i = 0; i < 99; i++) {
            deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, UUID.randomUUID());
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<ClaimPrekeyResponseDto> first = new AtomicReference<>();
        AtomicReference<ClaimPrekeyResponseDto> second = new AtomicReference<>();
        AtomicReference[] holders = {first, second};
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                    @SuppressWarnings("unchecked")
                    AtomicReference<ClaimPrekeyResponseDto> holder =
                            (AtomicReference<ClaimPrekeyResponseDto>) holders[index];
                    holder.set(deviceService.claimOneTimePrekey(
                            bob.getUserId(), aliceDevice, UUID.randomUUID()));
                } catch (Exception e) {
                    throw new AssertionError("Unexpected exception during concurrent claim", e);
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        int withPrekey = (first.get().getOneTimePrekey() != null ? 1 : 0)
                + (second.get().getOneTimePrekey() != null ? 1 : 0);
        assertEquals(1, withPrekey, "Exactly one concurrent claim must receive the last prekey");
        assertNotNull(first.get().getSignedPrekey());
        assertNotNull(second.get().getSignedPrekey());
        assertEquals(0, prekeyRepo.countByDeviceAndConsumedAtIsNull(
                deviceRepo.findById(aliceDevice).orElseThrow()));
    }

    @Test
    void concurrentSameCodeRecoveryResolvesToOneWinner() throws Exception {
        User user = createUser("e2ee_conc_rec");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(340, ClientPlatform.WEB));
        String code = first.getRecoveryCodes().get(0);
        deviceService.revokeDevice(user.getUserId(), first.getDevice().getDeviceId());


        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int seed = 341 + i;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                    RecoveryEnrollRequestDto request = new RecoveryEnrollRequestDto();
                    request.setRecoveryCode(code);
                    request.setDevice(E2eeTestKeys.enrollRequest(seed, ClientPlatform.ANDROID));
                    DeviceDto recovered = deviceService.recoverEnroll(
                            user.getUserId(), login.sessionId(), request);
                    if (recovered != null && recovered.getStatus() == DeviceStatus.ACTIVE) {
                        successes.incrementAndGet();
                    }
                } catch (InvalidRecoveryCodeException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError("Unexpected exception during concurrent recovery", e);
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(1, successes.get(), "Exactly one concurrent recovery must succeed");
        assertEquals(1, rejected.get(), "The racing recovery must be rejected");
        assertEquals(2, deviceRepo.count(), "Exactly one new device must exist");
    }
}
