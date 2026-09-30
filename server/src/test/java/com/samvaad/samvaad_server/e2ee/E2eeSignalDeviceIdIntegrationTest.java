package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo;
import com.samvaad.samvaad_server.e2ee.dto.ClaimPrekeyResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceListDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.RecipientDeviceDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceAlreadyExistsException;
import com.samvaad.samvaad_server.friendrequest.FriendRequestDto;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * Server-assigned Signal integer device ids (PART A): per-user allocation
 * from 1, monotonic increase, no reuse after revocation, distinct ids
 * under concurrent enrollment, rollback safety, DTO exposure, and UUID
 * remaining canonical for API paths and revocation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class E2eeSignalDeviceIdIntegrationTest {

    @Autowired
    private E2eeDeviceService deviceService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserService userService;

    @Autowired
    private FriendRequestService friendRequestService;


    @Autowired
    private com.samvaad.samvaad_server.messaging.ConversationRepo conversationRepo;

    @Autowired
    private com.samvaad.samvaad_server.friendrequest.FriendRequestRepo friendRequestRepo;

    @Autowired
    private E2eeDeviceRepo deviceRepo;

    @Autowired
    private E2eeOneTimePrekeyRepo prekeyRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo recoveryCodeRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.message.E2eeMessageRepo e2eeMessageRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.message.E2eeEnvelopeRepo e2eeEnvelopeRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.message.E2eeMailboxRepo e2eeMailboxRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.message.E2eeSyncCursorRepo e2eeSyncCursorRepo;

    @BeforeEach
    void setUp() {
        // Ciphertext transport tables precede conversations/users: RESTRICT
        // foreign keys forbid deleting a conversation or sender still
        // referenced by durable ciphertext rows.
        e2eeMailboxRepo.deleteAll();
        e2eeEnvelopeRepo.deleteAll();
        e2eeMessageRepo.deleteAll();
        e2eeSyncCursorRepo.deleteAll();
        prekeyRepo.deleteAll();
        recoveryCodeRepo.deleteAll();
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

    private EnrollDeviceResponseDto enroll(User user, UUID sessionId, int seed) {
        return deviceService.enrollDevice(
                user.getUserId(), sessionId, E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
    }

    private void befriend(User first, User second) {
        FriendRequestDto request = friendRequestService.sendRequest(first.getUserId(), second.getUsername());
        friendRequestService.acceptRequest(second.getUserId(), request.getRequestId());
    }

    @Test
    void allocatesFromOneAndIncrements() {
        User user = createUser("sig_alloc");
        List<Integer> ids = new ArrayList<>();
        for (int seed = 700; seed <= 702; seed++) {
            EnrollDeviceResponseDto response = enroll(user, login(user.getUsername()).sessionId(), seed);
            ids.add(response.getDevice().getSignalDeviceId());
        }
        assertEquals(List.of(1, 2, 3), ids);

        List<E2eeDevice> rows = deviceRepo.findByUserUserIdOrderByCreatedAtAsc(user.getUserId());
        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).getSignalDeviceId());
        assertEquals(3, rows.get(2).getSignalDeviceId());
    }

    @Test
    void allocationIsPerUserIndependent() {
        User first = createUser("sig_user_a");
        User second = createUser("sig_user_b");
        EnrollDeviceResponseDto a = enroll(first, login(first.getUsername()).sessionId(), 710);
        EnrollDeviceResponseDto b = enroll(second, login(second.getUsername()).sessionId(), 711);
        assertEquals(1, a.getDevice().getSignalDeviceId());
        assertEquals(1, b.getDevice().getSignalDeviceId());
    }

    @Test
    void revokedIdsAreNeverReused() {
        User user = createUser("sig_revoke");
        LoginResponseDto firstLogin = login(user.getUsername());
        EnrollDeviceResponseDto first = enroll(user, firstLogin.sessionId(), 720);
        EnrollDeviceResponseDto second = enroll(user, login(user.getUsername()).sessionId(), 721);
        assertEquals(1, first.getDevice().getSignalDeviceId());
        assertEquals(2, second.getDevice().getSignalDeviceId());

        // Approve the pending device so the account keeps an ACTIVE device
        // after the first one is revoked (revoking the only ACTIVE device
        // would force recovery enrollment instead).
        deviceService.approveDevice(
                user.getUserId(), firstLogin.sessionId(), second.getDevice().getDeviceId());
        deviceService.revokeDevice(user.getUserId(), first.getDevice().getDeviceId());

        EnrollDeviceResponseDto third = enroll(user, login(user.getUsername()).sessionId(), 722);
        assertEquals(3, third.getDevice().getSignalDeviceId());

        E2eeDevice revoked = deviceRepo.findById(first.getDevice().getDeviceId()).orElseThrow();
        assertEquals(1, revoked.getSignalDeviceId());

        Set<Integer> live = new HashSet<>();
        deviceRepo.findByUserUserIdOrderByCreatedAtAsc(user.getUserId()).forEach(d -> {
            assertTrue(live.add(d.getSignalDeviceId()), "signal ids must stay distinct");
        });
    }

    @Test
    void concurrentEnrollmentsReceiveDistinctIds() throws Exception {
        User user = createUser("sig_conc");
        int threads = 4;
        List<UUID> sessions = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            sessions.add(login(user.getUsername()).sessionId());
        }
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int seed = 730 + i;
            final UUID sessionId = sessions.get(i);
            futures.add(executor.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return enroll(user, sessionId, seed).getDevice().getSignalDeviceId();
            }));
        }
        Set<Integer> ids = new HashSet<>();
        for (Future<Integer> future : futures) {
            ids.add(future.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();
        assertEquals(Set.of(1, 2, 3, 4), ids);
        assertEquals(4, deviceRepo.findByUserUserIdOrderByCreatedAtAsc(user.getUserId()).size());
    }

    @Test
    void rollbackOnDuplicateLeavesNoInvalidMapping() {
        User user = createUser("sig_rollback");
        enroll(user, login(user.getUsername()).sessionId(), 740);
        assertThrows(DeviceAlreadyExistsException.class, () ->
                enroll(user, login(user.getUsername()).sessionId(), 740));

        EnrollDeviceResponseDto next = enroll(user, login(user.getUsername()).sessionId(), 741);
        assertEquals(2, next.getDevice().getSignalDeviceId());

        List<E2eeDevice> rows = deviceRepo.findByUserUserIdOrderByCreatedAtAsc(user.getUserId());
        assertEquals(2, rows.size());
        assertEquals(Set.of(1, 2),
                Set.of(rows.get(0).getSignalDeviceId(), rows.get(1).getSignalDeviceId()));
    }

    @Test
    void directoryClaimAndOwnerMappingsExposeSignalId() {
        User alice = createUser("sig_map_a");
        User bob = createUser("sig_map_b");
        LoginResponseDto aliceLogin = login(alice.getUsername());
        login(bob.getUsername());
        EnrollDeviceResponseDto enrolled = enroll(alice, aliceLogin.sessionId(), 750);
        UUID aliceDevice = enrolled.getDevice().getDeviceId();
        deviceService.uploadOneTimePrekeys(
                alice.getUserId(), aliceLogin.sessionId(), aliceDevice, E2eeTestKeys.uploadBatch(1));
        befriend(alice, bob);

        List<RecipientDeviceDto> directory =
                deviceService.getRecipientDevices(bob.getUserId(), alice.getUsername());
        assertEquals(1, directory.size());
        assertEquals(1, directory.get(0).getSignalDeviceId());
        assertEquals(aliceDevice, directory.get(0).getDeviceId());

        ClaimPrekeyResponseDto claimed =
                deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, UUID.randomUUID());
        assertEquals(1, claimed.getSignalDeviceId());

        DeviceListDto owner = deviceService.listDevices(alice.getUserId());
        assertEquals(1, owner.getDevices().size());
        assertEquals(1, owner.getDevices().get(0).getSignalDeviceId());
    }

    @Test
    void uuidRemainsCanonicalForRevocation() {
        User user = createUser("sig_uuid");
        EnrollDeviceResponseDto enrolled = enroll(user, login(user.getUsername()).sessionId(), 760);
        deviceService.revokeDevice(user.getUserId(), enrolled.getDevice().getDeviceId());
        assertEquals(1, deviceRepo.findById(enrolled.getDevice().getDeviceId())
                .orElseThrow().getSignalDeviceId());
    }
}
