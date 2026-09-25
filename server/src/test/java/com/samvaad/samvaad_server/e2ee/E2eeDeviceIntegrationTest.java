package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.RefreshTokenService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException;
import com.samvaad.samvaad_server.e2ee.device.DeviceStatus;
import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo;
import com.samvaad.samvaad_server.e2ee.dto.ClaimPrekeyResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceListDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollmentState;
import com.samvaad.samvaad_server.e2ee.dto.RecipientDeviceDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceAlreadyExistsException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceLimitExceededException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotActiveException;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryService;
import com.samvaad.samvaad_server.friendrequest.FriendRequestDto;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.session.SessionService;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * Device lifecycle end to end: bootstrap, pending enrollment, approval,
 * prekey upload/claim/replenish, directory, revocation, TTL expiry, and
 * user-deletion cleanup.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class E2eeDeviceIntegrationTest {

    @Autowired
    private E2eeDeviceService deviceService;

    @Autowired
    private E2eeRecoveryService recoveryService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private RefreshTokenService refreshTokenService;

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
    private E2eeRecoveryCodeRepo recoveryCodeRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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

    private EnrollDeviceResponseDto bootstrap(User user, LoginResponseDto login, int seed) {
        return deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
    }

    private void befriend(User first, User second) {
        FriendRequestDto request = friendRequestService.sendRequest(first.getUserId(), second.getUsername());
        friendRequestService.acceptRequest(second.getUserId(), request.getRequestId());
    }

    @Test
    void bootstrapCreatesActiveDeviceWithRecoveryCodesAndBoundSession() {
        User user = createUser("e2ee_boot");
        LoginResponseDto login = login(user.getUsername());

        EnrollDeviceResponseDto response = bootstrap(user, login, 1);

        assertEquals(DeviceStatus.ACTIVE, response.getDevice().getStatus());
        assertEquals(EnrollmentState.NEVER_ENROLLED, response.getEnrollmentState());
        assertNotNull(response.getRecoveryCodes());
        assertEquals(E2eePolicy.RECOVERY_CODES_PER_SET, response.getRecoveryCodes().size());
        assertEquals(response.getDevice().getDeviceId(),
                sessionRepo.findById(login.sessionId()).orElseThrow().getDeviceId());
        assertEquals(E2eePolicy.RECOVERY_CODES_PER_SET,
                recoveryCodeRepo.findUsableByUser(user).size());
        recoveryCodeRepo.findUsableByUser(user).forEach(code -> {
            assertTrue(code.getCodeHash().startsWith("$2"),
                    "recovery codes must be BCrypt hashes server-side");
            assertTrue(response.getRecoveryCodes().stream().noneMatch(plain ->
                    code.getCodeHash().contains(plain.replace("-", ""))));
        });
    }

    @Test
    void secondEnrollCreatesPendingWithoutCodes() {
        User user = createUser("e2ee_pending");
        LoginResponseDto firstLogin = login(user.getUsername());
        bootstrap(user, firstLogin, 10);

        LoginResponseDto secondLogin = login(user.getUsername());
        EnrollDeviceResponseDto response = deviceService.enrollDevice(
                user.getUserId(), secondLogin.sessionId(), E2eeTestKeys.enrollRequest(11, ClientPlatform.ANDROID));

        assertEquals(DeviceStatus.PENDING, response.getDevice().getStatus());
        assertEquals(EnrollmentState.ENROLLED_ACTIVE, response.getEnrollmentState());
        assertNull(response.getRecoveryCodes());
        assertEquals(response.getDevice().getDeviceId(),
                sessionRepo.findById(secondLogin.sessionId()).orElseThrow().getDeviceId());
    }

    @Test
    void duplicateIdentityKeyRejected() {
        User user = createUser("e2ee_dup");
        LoginResponseDto login = login(user.getUsername());
        bootstrap(user, login, 20);

        assertThrows(DeviceAlreadyExistsException.class, () -> deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(20, ClientPlatform.WEB)));
    }

    @Test
    void deviceLimitFiveEnforcedAndRevokedFreesSlot() {
        User user = createUser("e2ee_limit");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = bootstrap(user, login, 30);
        assertEquals(DeviceStatus.ACTIVE, first.getDevice().getStatus());
        UUID pendingId = null;
        for (int seed = 31; seed <= 34; seed++) {
            EnrollDeviceResponseDto enrolled = deviceService.enrollDevice(
                    user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
            pendingId = enrolled.getDevice().getDeviceId();
        }

        assertThrows(DeviceLimitExceededException.class, () -> deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(35, ClientPlatform.WEB)));

        // Revoking a PENDING device keeps one ACTIVE device, so the account
        // stays enrollable and the freed slot accepts a new enrollment.
        deviceService.revokeDevice(user.getUserId(), pendingId);
        EnrollDeviceResponseDto replacement = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(35, ClientPlatform.WEB));
        assertNotNull(replacement.getDevice().getDeviceId());
    }

    @Test
    void approvePendingWithTrustedSession() {
        User user = createUser("e2ee_approve");
        LoginResponseDto trustedLogin = login(user.getUsername());
        bootstrap(user, trustedLogin, 40);

        LoginResponseDto pendingLogin = login(user.getUsername());
        EnrollDeviceResponseDto pending = deviceService.enrollDevice(
                user.getUserId(), pendingLogin.sessionId(), E2eeTestKeys.enrollRequest(41, ClientPlatform.TUI));
        UUID pendingId = pending.getDevice().getDeviceId();

        DeviceDto approved = deviceService.approveDevice(
                user.getUserId(), trustedLogin.sessionId(), pendingId);
        assertEquals(DeviceStatus.ACTIVE, approved.getStatus());

        DeviceDto idempotent = deviceService.approveDevice(
                user.getUserId(), trustedLogin.sessionId(), pendingId);
        assertEquals(DeviceStatus.ACTIVE, idempotent.getStatus());
    }

    @Test
    void approveDeniedForUnboundSession() {
        User user = createUser("e2ee_approveno");
        LoginResponseDto trustedLogin = login(user.getUsername());
        bootstrap(user, trustedLogin, 50);

        LoginResponseDto pendingLogin = login(user.getUsername());
        UUID pendingId = deviceService.enrollDevice(
                user.getUserId(), pendingLogin.sessionId(), E2eeTestKeys.enrollRequest(51, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        LoginResponseDto strangerSession = login(user.getUsername());
        assertThrows(com.samvaad.samvaad_server.e2ee.exception.DeviceApprovalDeniedException.class,
                () -> deviceService.approveDevice(user.getUserId(), strangerSession.sessionId(), pendingId));
        assertEquals(DeviceStatus.PENDING,
                deviceRepo.findById(pendingId).orElseThrow().getStatus());
    }

    @Test
    void uploadRequiresActiveBoundSession() {
        User user = createUser("e2ee_upload");
        LoginResponseDto trustedLogin = login(user.getUsername());
        EnrollDeviceResponseDto first = bootstrap(user, trustedLogin, 60);
        UUID activeId = first.getDevice().getDeviceId();

        LoginResponseDto pendingLogin = login(user.getUsername());
        UUID pendingId = deviceService.enrollDevice(
                user.getUserId(), pendingLogin.sessionId(), E2eeTestKeys.enrollRequest(61, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        assertThrows(DeviceNotActiveException.class, () -> deviceService.uploadOneTimePrekeys(
                user.getUserId(), pendingLogin.sessionId(), pendingId, E2eeTestKeys.uploadBatch(1)));

        LoginResponseDto unbound = login(user.getUsername());
        assertThrows(ForbiddenOperationException.class, () -> deviceService.uploadOneTimePrekeys(
                user.getUserId(), unbound.sessionId(), activeId, E2eeTestKeys.uploadBatch(1)));

        assertThrows(com.samvaad.samvaad_server.e2ee.exception.InvalidPrekeyBatchException.class,
                () -> deviceService.uploadOneTimePrekeys(
                        user.getUserId(), trustedLogin.sessionId(), activeId, E2eeTestKeys.uploadBatch(1, 99)));

        DeviceDto uploaded = deviceService.uploadOneTimePrekeys(
                user.getUserId(), trustedLogin.sessionId(), activeId, E2eeTestKeys.uploadBatch(1));
        assertEquals(100, uploaded.getAvailablePrekeys());
    }

    @Test
    void claimConsumeReplenishLifecycle() {
        User alice = createUser("e2ee_alice");
        User bob = createUser("e2ee_bob");
        befriend(alice, bob);

        LoginResponseDto aliceLogin = login(alice.getUsername());
        UUID aliceDevice = bootstrap(alice, aliceLogin, 70).getDevice().getDeviceId();
        deviceService.uploadOneTimePrekeys(
                alice.getUserId(), aliceLogin.sessionId(), aliceDevice, E2eeTestKeys.uploadBatch(1));

        for (int i = 0; i < 81; i++) {
            ClaimPrekeyResponseDto claimed =
                    deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, UUID.randomUUID());
            assertNotNull(claimed.getOneTimePrekey());
            assertEquals(i + 1, claimed.getOneTimePrekey().getPrekeyId());
        }
        assertEquals(19, prekeyRepo.countByDeviceAndConsumedAtIsNull(
                deviceRepo.findById(aliceDevice).orElseThrow()));

        UUID replayId = UUID.randomUUID();
        ClaimPrekeyResponseDto first = deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, replayId);
        ClaimPrekeyResponseDto replay = deviceService.claimOneTimePrekey(bob.getUserId(), aliceDevice, replayId);
        assertEquals(first.getOneTimePrekey().getPrekeyId(), replay.getOneTimePrekey().getPrekeyId());
        assertEquals(first.getOneTimePrekey().getPublicKey(), replay.getOneTimePrekey().getPublicKey());

        DeviceDto replenished = deviceService.uploadOneTimePrekeys(
                alice.getUserId(), aliceLogin.sessionId(), aliceDevice, E2eeTestKeys.uploadBatch(101));
        assertEquals(118, replenished.getAvailablePrekeys());
    }

    @Test
    void claimEmptyPoolReturnsSignedPrekeyOnly() {
        User alice = createUser("e2ee_empty");
        User bob = createUser("e2ee_empty_bob");
        befriend(alice, bob);

        LoginResponseDto aliceLogin = login(alice.getUsername());
        UUID aliceDevice = bootstrap(alice, aliceLogin, 80).getDevice().getDeviceId();

        ClaimPrekeyResponseDto claimed = deviceService.claimOneTimePrekey(
                bob.getUserId(), aliceDevice, UUID.randomUUID());
        assertNull(claimed.getOneTimePrekey());
        assertNotNull(claimed.getSignedPrekey());
        assertNotNull(claimed.getSignedPrekeySignature());
    }

    @Test
    void directoryIsFriendshipGated() {
        User alice = createUser("e2ee_dir");
        User bob = createUser("e2ee_dir_bob");
        User stranger = createUser("e2ee_dir_stranger");

        LoginResponseDto aliceLogin = login(alice.getUsername());
        UUID aliceDevice = bootstrap(alice, aliceLogin, 90).getDevice().getDeviceId();
        deviceService.uploadOneTimePrekeys(
                alice.getUserId(), aliceLogin.sessionId(), aliceDevice, E2eeTestKeys.uploadBatch(1));

        assertThrows(ForbiddenOperationException.class,
                () -> deviceService.getRecipientDevices(stranger.getUserId(), alice.getUsername()));
        assertThrows(ForbiddenOperationException.class,
                () -> deviceService.claimOneTimePrekey(stranger.getUserId(), aliceDevice, UUID.randomUUID()));
        assertThrows(UserNotFoundException.class,
                () -> deviceService.getRecipientDevices(bob.getUserId(), "no_such_user"));

        // Non-friend callers are denied, not served an empty directory.
        assertThrows(ForbiddenOperationException.class,
                () -> deviceService.getRecipientDevices(bob.getUserId(), alice.getUsername()));

        befriend(alice, bob);
        List<RecipientDeviceDto> directory =
                deviceService.getRecipientDevices(bob.getUserId(), alice.getUsername());
        assertEquals(1, directory.size());
        assertTrue(directory.get(0).isHasAvailableOneTimePrekey());

        deviceService.revokeDevice(alice.getUserId(), aliceDevice);
        assertTrue(deviceService.getRecipientDevices(bob.getUserId(), alice.getUsername()).isEmpty());
    }

    @Test
    void revokeTerminatesBoundSessions() {
        User user = createUser("e2ee_revoke");
        LoginResponseDto login = login(user.getUsername());
        UUID deviceId = bootstrap(user, login, 100).getDevice().getDeviceId();

        deviceService.revokeDevice(user.getUserId(), deviceId);

        E2eeDevice revoked = deviceRepo.findById(deviceId).orElseThrow();
        assertEquals(DeviceStatus.REVOKED, revoked.getStatus());
        assertNotNull(revoked.getRevokedAt());
        assertNotNull(sessionRepo.findById(login.sessionId()).orElseThrow().getRevokedAt());
        assertThrows(InvalidRefreshTokenException.class,
                () -> refreshTokenService.refresh(login.refreshToken()));

        DeviceListDto list = deviceService.listDevices(user.getUserId());
        assertEquals(EnrollmentState.RECOVERY_REQUIRED, list.getEnrollmentState());
    }

    @Test
    void pendingTtlExpiryRevokesAbandonedEnrollment() {
        User user = createUser("e2ee_ttl");
        LoginResponseDto trustedLogin = login(user.getUsername());
        bootstrap(user, trustedLogin, 110);

        LoginResponseDto pendingLogin = login(user.getUsername());
        UUID pendingId = deviceService.enrollDevice(
                user.getUserId(), pendingLogin.sessionId(), E2eeTestKeys.enrollRequest(111, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        E2eeDevice pending = deviceRepo.findById(pendingId).orElseThrow();
        // created_at is immutable through JPA (updatable=false), so backdate
        // the row directly to simulate an abandoned enrollment.
        jdbcTemplate.update("UPDATE e2ee_devices SET created_at = ? WHERE device_id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(8)), pendingId);
        assertTrue(deviceRepo.findById(pendingId).orElseThrow().getCreatedAt()
                .isBefore(LocalDateTime.now().minusDays(7)));

        DeviceListDto list = deviceService.listDevices(user.getUserId());
        assertEquals(DeviceStatus.REVOKED, deviceRepo.findById(pendingId).orElseThrow().getStatus());
        assertNotNull(sessionRepo.findById(pendingLogin.sessionId()).orElseThrow().getRevokedAt());
        assertEquals(EnrollmentState.ENROLLED_ACTIVE, list.getEnrollmentState());
    }

    @Test
    void userDeletionRemovesE2eeRows() {
        User user = createUser("e2ee_delete");
        LoginResponseDto login = login(user.getUsername());
        UUID deviceId = bootstrap(user, login, 120).getDevice().getDeviceId();
        deviceService.uploadOneTimePrekeys(
                user.getUserId(), login.sessionId(), deviceId, E2eeTestKeys.uploadBatch(1));
        assertEquals(1, deviceRepo.count());
        assertEquals(100, prekeyRepo.count());
        assertEquals(25, recoveryCodeRepo.count());

        userService.deleteUser(user.getUserId());

        assertEquals(0, deviceRepo.count());
        assertEquals(0, prekeyRepo.count());
        assertEquals(0, recoveryCodeRepo.count());
        assertEquals(0, sessionRepo.count());

        // Second user proves rows are scoped: bootstrap still works afterwards.
        User other = createUser("e2ee_delete_other");
        LoginResponseDto otherLogin = login(other.getUsername());
        assertNotNull(bootstrap(other, otherLogin, 121).getDevice().getDeviceId());
    }

    @Test
    void sessionTrustDerivedFromBoundActiveDevice() {
        User user = createUser("e2ee_trust");
        LoginResponseDto trustedLogin = login(user.getUsername());
        UUID activeId = bootstrap(user, trustedLogin, 130).getDevice().getDeviceId();

        LoginResponseDto pendingLogin = login(user.getUsername());
        UUID pendingId = deviceService.enrollDevice(
                user.getUserId(), pendingLogin.sessionId(), E2eeTestKeys.enrollRequest(131, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        // A PENDING-bound session cannot approve even its own device.
        assertThrows(com.samvaad.samvaad_server.e2ee.exception.DeviceApprovalDeniedException.class,
                () -> deviceService.approveDevice(user.getUserId(), pendingLogin.sessionId(), pendingId));
        // The ACTIVE-bound enrollment session can.
        assertEquals(DeviceStatus.ACTIVE, deviceService
                .approveDevice(user.getUserId(), trustedLogin.sessionId(), pendingId).getStatus());
        assertNotEquals(activeId, pendingId);
    }

    @Test
    void maxSessionsConstantUnaffected() {
        assertEquals(5, SessionService.MAX_ACTIVE_SESSIONS);
        assertEquals(5, E2eePolicy.MAX_ENROLLED_DEVICES);
    }
}
