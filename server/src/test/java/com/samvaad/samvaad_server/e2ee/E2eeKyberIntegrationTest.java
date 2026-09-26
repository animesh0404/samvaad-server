package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
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
import com.samvaad.samvaad_server.e2ee.device.DeviceStatus;
import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo;
import com.samvaad.samvaad_server.e2ee.dto.ClaimPrekeyResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceRequestDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.RecipientDeviceDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceAlreadyExistsException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotActiveException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidKeyMaterialException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidPrekeyBatchException;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
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
 * Server-side last-resort Kyber (PQXDH) public-material foundation:
 * enrollment validation, friendship-gated directory, reusable claim
 * transport, atomic replacement, lifecycle gating, and the
 * cryptographically-blind boundary (public material only, never consumed
 * by the EC one-time-prekey claim).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class E2eeKyberIntegrationTest {

    @Autowired
    private E2eeDeviceService deviceService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserService userService;

    @Autowired
    private FriendRequestService friendRequestService;

    @Autowired
    private com.samvaad.samvaad_server.messaging.MessageRepo messageRepo;

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
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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

    private EnrollDeviceResponseDto bootstrap(User user, LoginResponseDto login, int seed) {
        return deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
    }

    private void befriend(User first, User second) {
        FriendRequestDto request = friendRequestService.sendRequest(first.getUserId(), second.getUsername());
        friendRequestService.acceptRequest(second.getUserId(), request.getRequestId());
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    @Test
    void enrollRequiresKyberMaterial() {
        User user = createUser("kyb_req");
        LoginResponseDto login = login(user.getUsername());

        EnrollDeviceRequestDto missing = E2eeTestKeys.enrollRequest(500, ClientPlatform.WEB);
        missing.setKyberPrekeyId(null);
        missing.setKyberPrekey(null);
        missing.setKyberPrekeySignature(null);
        assertThrows(InvalidPrekeyBatchException.class, () -> deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), missing));
    }

    @Test
    void enrollRejectsMalformedKyber() {
        User user = createUser("kyb_malformed");
        LoginResponseDto login = login(user.getUsername());

        EnrollDeviceRequestDto badBase64 = E2eeTestKeys.enrollRequest(501, ClientPlatform.WEB);
        badBase64.setKyberPrekey("!!!not-base64!!!");
        assertThrows(InvalidKeyMaterialException.class, () -> deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), badBase64));

        LoginResponseDto fresh = login(user.getUsername());
        EnrollDeviceRequestDto blankSig = E2eeTestKeys.enrollRequest(502, ClientPlatform.WEB);
        blankSig.setKyberPrekeySignature("  ");
        assertThrows(InvalidKeyMaterialException.class, () -> deviceService.enrollDevice(
                user.getUserId(), fresh.sessionId(), blankSig));
    }

    @Test
    void enrollPersistsKyberPublicMaterial() {
        User user = createUser("kyb_persist");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto response = bootstrap(user, login, 510);

        DeviceDto dto = response.getDevice();
        assertEquals(4000 + 510, dto.getKyberPrekeyId());
        assertNotNull(dto.getKyberPrekey());
        assertNotNull(dto.getKyberPrekeySignature());

        E2eeDevice entity = deviceRepo.findById(dto.getDeviceId()).orElseThrow();
        assertEquals(4000 + 510, entity.getKyberPrekeyId());
        assertArrayEquals(decode(dto.getKyberPrekey()), entity.getKyberPrekey());
        assertArrayEquals(decode(dto.getKyberPrekeySignature()), entity.getKyberPrekeySignature());
    }

    @Test
    void duplicateKyberKeyRejected() {
        User user = createUser("kyb_dup");
        LoginResponseDto login = login(user.getUsername());
        bootstrap(user, login, 520);

        LoginResponseDto fresh = login(user.getUsername());
        EnrollDeviceRequestDto reuse = E2eeTestKeys.enrollRequest(521, ClientPlatform.WEB);
        EnrollDeviceRequestDto first = E2eeTestKeys.enrollRequest(520, ClientPlatform.WEB);
        reuse.setKyberPrekey(first.getKyberPrekey());
        assertThrows(DeviceAlreadyExistsException.class, () -> deviceService.enrollDevice(
                user.getUserId(), fresh.sessionId(), reuse));
    }

    @Test
    void directoryExposesKyberToFriendsOnly() {
        User alice = createUser("kyb_dir_a");
        User bob = createUser("kyb_dir_b");
        User stranger = createUser("kyb_dir_s");
        LoginResponseDto aliceLogin = login(alice.getUsername());
        login(bob.getUsername());
        login(stranger.getUsername());
        EnrollDeviceResponseDto enrolled = bootstrap(alice, aliceLogin, 530);
        deviceService.uploadOneTimePrekeys(
                alice.getUserId(), aliceLogin.sessionId(),
                enrolled.getDevice().getDeviceId(), E2eeTestKeys.uploadBatch(1));

        assertThrows(ForbiddenOperationException.class,
                () -> deviceService.getRecipientDevices(stranger.getUserId(), alice.getUsername()));

        befriend(alice, bob);
        List<RecipientDeviceDto> directory =
                deviceService.getRecipientDevices(bob.getUserId(), alice.getUsername());
        assertEquals(1, directory.size());
        assertEquals(4000 + 530, directory.get(0).getKyberPrekeyId());
        assertNotNull(directory.get(0).getKyberPrekey());
        assertNotNull(directory.get(0).getKyberPrekeySignature());
    }

    @Test
    void claimIncludesReusableKyberAcrossEcConsumption() {
        User alice = createUser("kyb_claim_a");
        User bob = createUser("kyb_claim_b");
        LoginResponseDto aliceLogin = login(alice.getUsername());
        login(bob.getUsername());
        EnrollDeviceResponseDto enrolled = bootstrap(alice, aliceLogin, 540);
        UUID aliceDevice = enrolled.getDevice().getDeviceId();
        deviceService.uploadOneTimePrekeys(
                alice.getUserId(), aliceLogin.sessionId(), aliceDevice, E2eeTestKeys.uploadBatch(1));
        befriend(alice, bob);

        ClaimPrekeyResponseDto first =
                deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, UUID.randomUUID());
        assertNotNull(first.getOneTimePrekey());
        assertEquals(4000 + 540, first.getKyberPrekeyId());
        assertNotNull(first.getKyberPrekey());
        assertNotNull(first.getKyberPrekeySignature());

        // A second claim consumes a different EC key but the same Kyber key.
        ClaimPrekeyResponseDto second =
                deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, UUID.randomUUID());
        assertNotNull(second.getOneTimePrekey());
        assertTrue(!first.getOneTimePrekey().getPrekeyId().equals(second.getOneTimePrekey().getPrekeyId()),
                "EC one-time keys must differ across claims");
        assertEquals(first.getKyberPrekeyId(), second.getKyberPrekeyId());
        assertEquals(first.getKyberPrekey(), second.getKyberPrekey());
        assertEquals(first.getKyberPrekeySignature(), second.getKyberPrekeySignature());
    }

    @Test
    void claimReplayReturnsIdenticalKyberAndEcKey() {
        User alice = createUser("kyb_replay_a");
        User bob = createUser("kyb_replay_b");
        LoginResponseDto aliceLogin = login(alice.getUsername());
        login(bob.getUsername());
        EnrollDeviceResponseDto enrolled = bootstrap(alice, aliceLogin, 550);
        UUID aliceDevice = enrolled.getDevice().getDeviceId();
        deviceService.uploadOneTimePrekeys(
                alice.getUserId(), aliceLogin.sessionId(), aliceDevice, E2eeTestKeys.uploadBatch(1));
        befriend(alice, bob);

        UUID replayId = UUID.randomUUID();
        ClaimPrekeyResponseDto first =
                deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, replayId);
        ClaimPrekeyResponseDto replay =
                deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, replayId);
        assertEquals(first.getOneTimePrekey().getPrekeyId(), replay.getOneTimePrekey().getPrekeyId());
        assertEquals(first.getKyberPrekeyId(), replay.getKyberPrekeyId());
        assertEquals(first.getKyberPrekey(), replay.getKyberPrekey());
        assertEquals(first.getKyberPrekeySignature(), replay.getKyberPrekeySignature());
    }

    @Test
    void claimOnEmptyPoolStillReturnsKyber() {
        User alice = createUser("kyb_empty_a");
        User bob = createUser("kyb_empty_b");
        LoginResponseDto aliceLogin = login(alice.getUsername());
        login(bob.getUsername());
        EnrollDeviceResponseDto enrolled = bootstrap(alice, aliceLogin, 560);
        befriend(alice, bob);

        ClaimPrekeyResponseDto claimed = deviceService.claimOneTimePrekey(
                bob.getUserId(), enrolled.getDevice().getDeviceId(), UUID.randomUUID());
        assertNull(claimed.getOneTimePrekey());
        assertEquals(4000 + 560, claimed.getKyberPrekeyId());
        assertNotNull(claimed.getKyberPrekey());
    }

    @Test
    void concurrentClaimsShareKyberWhileConsumingDistinctEcKeys() throws Exception {
        User alice = createUser("kyb_conc_a");
        User bob = createUser("kyb_conc_b");
        LoginResponseDto aliceLogin = login(alice.getUsername());
        login(bob.getUsername());
        EnrollDeviceResponseDto enrolled = bootstrap(alice, aliceLogin, 570);
        UUID aliceDevice = enrolled.getDevice().getDeviceId();
        deviceService.uploadOneTimePrekeys(
                alice.getUserId(), aliceLogin.sessionId(), aliceDevice, E2eeTestKeys.uploadBatch(1));
        befriend(alice, bob);

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<ClaimPrekeyResponseDto>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final UUID requestId = UUID.randomUUID();
            futures.add(executor.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, requestId);
            }));
        }
        Set<Integer> ecIds = new HashSet<>();
        Set<String> kyberSeen = new HashSet<>();
        for (Future<ClaimPrekeyResponseDto> future : futures) {
            ClaimPrekeyResponseDto claimed = future.get(60, TimeUnit.SECONDS);
            ecIds.add(claimed.getOneTimePrekey().getPrekeyId());
            kyberSeen.add(claimed.getKyberPrekeyId() + ":" + claimed.getKyberPrekey());
        }
        executor.shutdown();
        assertEquals(threads, ecIds.size(), "each claim must consume a distinct EC key");
        assertEquals(1, kyberSeen.size(), "all claims must return the identical Kyber key");
    }

    @Test
    void rotationReplacesAtomicallyOnActiveBoundSession() {
        User user = createUser("kyb_rot");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto enrolled = bootstrap(user, login, 580);
        UUID deviceId = enrolled.getDevice().getDeviceId();

        DeviceDto rotated = deviceService.replaceKyberPrekey(
                user.getUserId(), login.sessionId(), deviceId,
                E2eeTestKeys.rotateKyberRequest(7777, 5800));
        assertEquals(7777, rotated.getKyberPrekeyId());
        assertNotNull(rotated.getKyberPrekey());

        E2eeDevice entity = deviceRepo.findById(deviceId).orElseThrow();
        assertEquals(7777, entity.getKyberPrekeyId());
        assertArrayEquals(decode(rotated.getKyberPrekey()), entity.getKyberPrekey());

        // Identical resubmission is a no-op success.
        DeviceDto noop = deviceService.replaceKyberPrekey(
                user.getUserId(), login.sessionId(), deviceId,
                E2eeTestKeys.rotateKyberRequest(7777, 5800));
        assertEquals(7777, noop.getKyberPrekeyId());

        // Identifier reuse with different bytes is rejected.
        assertThrows(InvalidKeyMaterialException.class, () -> deviceService.replaceKyberPrekey(
                user.getUserId(), login.sessionId(), deviceId,
                E2eeTestKeys.rotateKyberRequest(7777, 5900)));
    }

    @Test
    void rotationRejectedForForeignOrDuplicateKeys() {
        User user = createUser("kyb_rot_dup");
        User other = createUser("kyb_rot_other");
        LoginResponseDto login = login(user.getUsername());
        LoginResponseDto otherLogin = login(other.getUsername());
        EnrollDeviceResponseDto enrolled = bootstrap(user, login, 600);
        EnrollDeviceResponseDto otherEnrolled = bootstrap(other, otherLogin, 601);
        UUID deviceId = enrolled.getDevice().getDeviceId();

        // Another device's key material cannot be adopted.
        EnrollDeviceRequestDto otherKeys = E2eeTestKeys.enrollRequest(601, ClientPlatform.WEB);
        com.samvaad.samvaad_server.e2ee.dto.RotateKyberPrekeyRequestDto clash =
                E2eeTestKeys.rotateKyberRequest(8888, 0);
        clash.setKyberPrekey(otherKeys.getKyberPrekey());
        assertThrows(DeviceAlreadyExistsException.class, () -> deviceService.replaceKyberPrekey(
                user.getUserId(), login.sessionId(), deviceId, clash));

        // Another user's session cannot replace this device's key.
        assertThrows(ForbiddenOperationException.class, () -> deviceService.replaceKyberPrekey(
                other.getUserId(), otherLogin.sessionId(), deviceId,
                E2eeTestKeys.rotateKyberRequest(8889, 6100)));
        // An unbound session of the same user cannot replace either.
        LoginResponseDto unbound = login(user.getUsername());
        assertThrows(ForbiddenOperationException.class, () -> deviceService.replaceKyberPrekey(
                user.getUserId(), unbound.sessionId(), deviceId,
                E2eeTestKeys.rotateKyberRequest(8890, 6200)));
        assertEquals(otherEnrolled.getDevice().getDeviceId(),
                otherEnrolled.getDevice().getDeviceId());
    }

    @Test
    void rotationRejectedWhilePendingOrRevoked() {
        User user = createUser("kyb_rot_life");
        LoginResponseDto login = login(user.getUsername());
        bootstrap(user, login, 610);

        LoginResponseDto pendingLogin = login(user.getUsername());
        EnrollDeviceResponseDto pending = deviceService.enrollDevice(
                user.getUserId(), pendingLogin.sessionId(),
                E2eeTestKeys.enrollRequest(611, ClientPlatform.WEB));
        assertEquals(DeviceStatus.PENDING, pending.getDevice().getStatus());
        assertThrows(DeviceNotActiveException.class, () -> deviceService.replaceKyberPrekey(
                user.getUserId(), pendingLogin.sessionId(), pending.getDevice().getDeviceId(),
                E2eeTestKeys.rotateKyberRequest(9001, 6300)));

        deviceService.revokeDevice(user.getUserId(), pending.getDevice().getDeviceId());
        assertThrows(DeviceNotActiveException.class, () -> deviceService.replaceKyberPrekey(
                user.getUserId(), pendingLogin.sessionId(), pending.getDevice().getDeviceId(),
                E2eeTestKeys.rotateKyberRequest(9002, 6400)));
    }

    @Test
    void legacyRowWithoutKyberMapsToNull() {
        User user = createUser("kyb_legacy");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto enrolled = bootstrap(user, login, 620);
        UUID deviceId = enrolled.getDevice().getDeviceId();

        jdbcTemplate.update(
                "UPDATE e2ee_devices SET kyber_prekey_id = NULL, kyber_prekey = NULL,"
                        + " kyber_prekey_signature = NULL WHERE device_id = ?",
                deviceId);

        E2eeDevice reloaded = deviceRepo.findById(deviceId).orElseThrow();
        assertNull(reloaded.getKyberPrekeyId());
        User friend = createUser("kyb_legacy_f");
        login(friend.getUsername());
        befriend(user, friend);
        List<RecipientDeviceDto> directory =
                deviceService.getRecipientDevices(friend.getUserId(), user.getUsername());
        assertEquals(1, directory.size());
        assertNull(directory.get(0).getKyberPrekeyId());
        assertNull(directory.get(0).getKyberPrekey());
    }

    @Test
    void noPrivateMaterialColumnsExist() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'e2ee_devices'",
                String.class);
        for (String column : columns) {
            String lower = column.toLowerCase();
            assertTrue(!lower.contains("secret") && !lower.contains("private"),
                    "public-only table must not hold private material: " + column);
        }
        assertTrue(columns.contains("kyber_prekey_id"));
        assertTrue(columns.contains("kyber_prekey"));
        assertTrue(columns.contains("kyber_prekey_signature"));
    }
}
