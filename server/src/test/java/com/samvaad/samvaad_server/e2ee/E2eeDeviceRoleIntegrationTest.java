package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.e2ee.device.DeviceRole;
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
import com.samvaad.samvaad_server.e2ee.dto.RecoveryEnrollRequestDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceLimitExceededException;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo;
import com.samvaad.samvaad_server.friendrequest.FriendRequestDto;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.RevocationReason;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.session.SessionService;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * Primary/Companion role cardinality (ADR 0025 slice): server-assigned
 * roles, 1 non-REVOKED PRIMARY + up to 4 non-REVOKED COMPANIONS within the
 * 5-device ceiling, locked recovery-role rule, no succession, and unchanged
 * approval/revocation behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class E2eeDeviceRoleIntegrationTest {

    private static final EnumSet<DeviceStatus> NON_REVOKED =
            EnumSet.of(DeviceStatus.PENDING, DeviceStatus.ACTIVE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private E2eeDeviceService deviceService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserService userService;

    @Autowired
    private FriendRequestService friendRequestService;

    @Autowired
    private SessionService sessionService;

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
    private com.samvaad.samvaad_server.messaging.ConversationRepo conversationRepo;

    @Autowired
    private com.samvaad.samvaad_server.friendrequest.FriendRequestRepo friendRequestRepo;

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

    private void logout(UUID sessionId) {
        sessionService.revokeSession(sessionId, RevocationReason.USER_LOGOUT);
    }

    private EnrollDeviceResponseDto bootstrap(User user, LoginResponseDto login, int seed) {
        return deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
    }

    /** Enrolls one device from a fresh session and logs that session out. */
    private EnrollDeviceResponseDto enrollAndRelease(User user, int seed) {
        LoginResponseDto fresh = login(user.getUsername());
        EnrollDeviceResponseDto response = deviceService.enrollDevice(
                user.getUserId(), fresh.sessionId(), E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
        logout(fresh.sessionId());
        return response;
    }

    private void befriend(User first, User second) {
        FriendRequestDto request = friendRequestService.sendRequest(first.getUserId(), second.getUsername());
        friendRequestService.acceptRequest(second.getUserId(), request.getRequestId());
    }

    private RecoveryEnrollRequestDto recoveryRequest(String code, int seed) {
        RecoveryEnrollRequestDto request = new RecoveryEnrollRequestDto();
        request.setRecoveryCode(code);
        request.setDevice(E2eeTestKeys.enrollRequest(seed, ClientPlatform.ANDROID));
        return request;
    }

    /**
     * Stages a device row directly, bypassing the service, for states the
     * service never produces on its own (e.g. a non-REVOKED PRIMARY that is
     * not ACTIVE, as left behind by abandoned backfilled enrollments).
     */
    private E2eeDevice stageDevice(User user, int seed, int signalDeviceId,
            DeviceRole role, DeviceStatus status) {
        E2eeDevice device = new E2eeDevice();
        device.setUser(user);
        device.setSignalDeviceId(signalDeviceId);
        device.setRegistrationId(1000 + seed);
        device.setDeviceIdentityPublicKey(
                E2eeMapper.decodeBase64("k", E2eeTestKeys.key(seed * 10 + 1)));
        device.setSignedPrekeyId(2000 + seed);
        device.setSignedPrekey(E2eeMapper.decodeBase64("k", E2eeTestKeys.key(seed * 10 + 2)));
        device.setSignedPrekeySignature(
                E2eeMapper.decodeBase64("k", E2eeTestKeys.key(seed * 10 + 3, 64)));
        device.setDeviceRole(role);
        device.setStatus(status);
        device.setClientPlatform(ClientPlatform.WEB);
        device.setClientName("Staged");
        device.setClientVersion("1.0.0");
        device.setLastActiveAt(LocalDateTime.now());
        return deviceRepo.saveAndFlush(device);
    }

    private long countRole(User user, DeviceRole role) {
        return deviceRepo.countByUserAndStatusInAndDeviceRole(user, NON_REVOKED, role);
    }

    @Test
    void firstEnrollmentIsPrimaryActive() {
        User user = createUser("role_first");
        LoginResponseDto login = login(user.getUsername());

        EnrollDeviceResponseDto response = bootstrap(user, login, 700);

        assertEquals(DeviceRole.PRIMARY, response.getDevice().getDeviceRole());
        assertEquals(DeviceStatus.ACTIVE, response.getDevice().getStatus());
        assertEquals(EnrollmentState.NEVER_ENROLLED, response.getEnrollmentState());
        assertNotNull(response.getRecoveryCodes());
        assertEquals(DeviceRole.PRIMARY,
                deviceRepo.findById(response.getDevice().getDeviceId()).orElseThrow().getDeviceRole());
    }

    @Test
    void secondEnrollmentIsCompanionPending() {
        User user = createUser("role_second");
        LoginResponseDto firstLogin = login(user.getUsername());
        bootstrap(user, firstLogin, 710);

        LoginResponseDto secondLogin = login(user.getUsername());
        EnrollDeviceResponseDto response = deviceService.enrollDevice(
                user.getUserId(), secondLogin.sessionId(),
                E2eeTestKeys.enrollRequest(711, ClientPlatform.ANDROID));

        assertEquals(DeviceRole.COMPANION, response.getDevice().getDeviceRole());
        assertEquals(DeviceStatus.PENDING, response.getDevice().getStatus());
        assertEquals(EnrollmentState.ENROLLED_ACTIVE, response.getEnrollmentState());
        assertNull(response.getRecoveryCodes());
    }

    @Test
    void exactlyOneNonRevokedPrimary() {
        User user = createUser("role_oneprimary");
        LoginResponseDto login = login(user.getUsername());
        bootstrap(user, login, 720);
        enrollAndRelease(user, 721);
        enrollAndRelease(user, 722);

        assertEquals(1, countRole(user, DeviceRole.PRIMARY));
        assertEquals(2, countRole(user, DeviceRole.COMPANION));
        List<E2eeDevice> primaries = deviceRepo.findByUserUserIdOrderByCreatedAtAsc(user.getUserId())
                .stream()
                .filter(d -> d.getDeviceRole() == DeviceRole.PRIMARY && !d.isRevoked())
                .toList();
        assertEquals(1, primaries.size());
        assertEquals(DeviceStatus.ACTIVE, primaries.get(0).getStatus());
    }

    @Test
    void maximumFourNonRevokedCompanions() {
        User user = createUser("role_fourcomp");
        LoginResponseDto login = login(user.getUsername());
        bootstrap(user, login, 730);
        logout(login.sessionId());
        for (int seed = 731; seed <= 734; seed++) {
            EnrollDeviceResponseDto enrolled = enrollAndRelease(user, seed);
            assertEquals(DeviceRole.COMPANION, enrolled.getDevice().getDeviceRole());
        }
        assertEquals(4, countRole(user, DeviceRole.COMPANION));

        LoginResponseDto sixth = login(user.getUsername());
        assertThrows(DeviceLimitExceededException.class, () -> deviceService.enrollDevice(
                user.getUserId(), sixth.sessionId(), E2eeTestKeys.enrollRequest(735, ClientPlatform.WEB)));
        assertEquals(4, countRole(user, DeviceRole.COMPANION));
        assertEquals(1, countRole(user, DeviceRole.PRIMARY));
    }

    @Test
    void maximumFiveTotalNonRevokedDevices() {
        assertEquals(1, E2eePolicy.MAX_PRIMARY_DEVICES);
        assertEquals(4, E2eePolicy.MAX_COMPANION_DEVICES);
        assertEquals(5, E2eePolicy.MAX_ENROLLED_DEVICES);

        User user = createUser("role_fivetotal");
        LoginResponseDto login = login(user.getUsername());
        bootstrap(user, login, 740);
        logout(login.sessionId());
        for (int seed = 741; seed <= 744; seed++) {
            enrollAndRelease(user, seed);
        }
        assertEquals(5, deviceRepo.countByUserAndStatusIn(user, NON_REVOKED));

        LoginResponseDto sixth = login(user.getUsername());
        assertThrows(DeviceLimitExceededException.class, () -> deviceService.enrollDevice(
                user.getUserId(), sixth.sessionId(), E2eeTestKeys.enrollRequest(745, ClientPlatform.WEB)));
        assertEquals(5, deviceRepo.countByUserAndStatusIn(user, NON_REVOKED));
    }

    @Test
    void revokedCompanionFreesCompanionSlot() {
        User user = createUser("role_freecomp");
        LoginResponseDto login = login(user.getUsername());
        bootstrap(user, login, 750);
        logout(login.sessionId());
        UUID companionId = null;
        for (int seed = 751; seed <= 754; seed++) {
            companionId = enrollAndRelease(user, seed).getDevice().getDeviceId();
        }
        assertEquals(4, countRole(user, DeviceRole.COMPANION));

        deviceService.revokeDevice(user.getUserId(), companionId);
        assertEquals(3, countRole(user, DeviceRole.COMPANION));

        EnrollDeviceResponseDto replacement = enrollAndRelease(user, 755);
        assertEquals(DeviceRole.COMPANION, replacement.getDevice().getDeviceRole());
        assertEquals(4, countRole(user, DeviceRole.COMPANION));
        assertEquals(1, countRole(user, DeviceRole.PRIMARY));
    }

    @Test
    void revokedPrimaryDoesNotPromoteAnyone() {
        User user = createUser("role_nopromote");
        LoginResponseDto primaryLogin = login(user.getUsername());
        UUID primaryId = bootstrap(user, primaryLogin, 760).getDevice().getDeviceId();

        LoginResponseDto companionLogin = login(user.getUsername());
        UUID companionId = deviceService.enrollDevice(
                user.getUserId(), companionLogin.sessionId(),
                E2eeTestKeys.enrollRequest(761, ClientPlatform.WEB)).getDevice().getDeviceId();
        deviceService.approveDevice(user.getUserId(), primaryLogin.sessionId(), companionId);

        deviceService.revokeDevice(user.getUserId(), primaryId);

        E2eeDevice revokedPrimary = deviceRepo.findById(primaryId).orElseThrow();
        assertEquals(DeviceStatus.REVOKED, revokedPrimary.getStatus());
        assertEquals(DeviceRole.PRIMARY, revokedPrimary.getDeviceRole());

        E2eeDevice companion = deviceRepo.findById(companionId).orElseThrow();
        assertEquals(DeviceStatus.ACTIVE, companion.getStatus());
        assertEquals(DeviceRole.COMPANION, companion.getDeviceRole());

        assertFalse(deviceRepo.existsByUserUserIdAndDeviceRoleAndStatusIn(
                user.getUserId(), DeviceRole.PRIMARY, NON_REVOKED));
        assertEquals(EnrollmentState.ENROLLED_ACTIVE,
                deviceService.listDevices(user.getUserId()).getEnrollmentState());
    }

    @Test
    void recoveryWithSurvivingPrimaryCreatesCompanion() {
        User user = createUser("role_recvcomp");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = bootstrap(user, login, 770);
        String code = first.getRecoveryCodes().get(0);

        // A surviving non-REVOKED PRIMARY that is not ACTIVE (as left behind
        // by an abandoned backfilled enrollment): revoke the ACTIVE PRIMARY
        // row, then stage a PRIMARY/PENDING row directly.
        deviceService.revokeDevice(user.getUserId(), first.getDevice().getDeviceId());
        stageDevice(user, 771, 2, DeviceRole.PRIMARY, DeviceStatus.PENDING);
        assertEquals(EnrollmentState.RECOVERY_REQUIRED,
                deviceService.listDevices(user.getUserId()).getEnrollmentState());

        LoginResponseDto fresh = login(user.getUsername());
        DeviceDto recovered = deviceService.recoverEnroll(
                user.getUserId(), fresh.sessionId(), recoveryRequest(code, 772));

        assertEquals(DeviceStatus.ACTIVE, recovered.getStatus());
        assertEquals(DeviceRole.COMPANION, recovered.getDeviceRole());
        assertEquals(1, countRole(user, DeviceRole.PRIMARY));
    }

    @Test
    void recoveryWithNoSurvivingPrimaryCreatesPrimary() {
        User user = createUser("role_recvprim");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = bootstrap(user, login, 780);
        String code = first.getRecoveryCodes().get(0);
        UUID primaryId = first.getDevice().getDeviceId();
        deviceService.revokeDevice(user.getUserId(), primaryId);

        LoginResponseDto fresh = login(user.getUsername());
        DeviceDto recovered = deviceService.recoverEnroll(
                user.getUserId(), fresh.sessionId(), recoveryRequest(code, 781));

        assertEquals(DeviceStatus.ACTIVE, recovered.getStatus());
        assertEquals(DeviceRole.PRIMARY, recovered.getDeviceRole());
        assertEquals(1, countRole(user, DeviceRole.PRIMARY));

        E2eeDevice revoked = deviceRepo.findById(primaryId).orElseThrow();
        assertEquals(DeviceRole.PRIMARY, revoked.getDeviceRole());
    }

    @Test
    void databaseBackstopRejectsSecondNonRevokedPrimary() {
        User user = createUser("role_backstop");
        LoginResponseDto login = login(user.getUsername());
        bootstrap(user, login, 790);

        // A second non-REVOKED PRIMARY violates the partial-unique index,
        // even though the service layer would never assign it.
        assertThrows(DataIntegrityViolationException.class,
                () -> stageDevice(user, 791, 2, DeviceRole.PRIMARY, DeviceStatus.ACTIVE));

        // A REVOKED PRIMARY is outside the partial index and persists fine.
        E2eeDevice revokedPrimary = stageDevice(user, 792, 3, DeviceRole.PRIMARY, DeviceStatus.REVOKED);
        assertEquals(DeviceRole.PRIMARY, revokedPrimary.getDeviceRole());
        assertEquals(1, countRole(user, DeviceRole.PRIMARY));
    }

    @Test
    void concurrentFirstEnrollmentCreatesSinglePrimary() throws Exception {
        User user = createUser("role_concfirst");
        int attempts = 3;
        List<UUID> sessions = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            sessions.add(login(user.getUsername()).sessionId());
        }
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CyclicBarrier barrier = new CyclicBarrier(attempts);
        AtomicInteger successes = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            final int seed = 800 + i;
            final UUID sessionId = sessions.get(i);
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                    deviceService.enrollDevice(user.getUserId(), sessionId,
                            E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
                    successes.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError("Unexpected exception during concurrent first enrollment", e);
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(attempts, successes.get(), "All concurrent first enrollments must succeed");
        assertEquals(1, countRole(user, DeviceRole.PRIMARY));
        assertEquals(attempts - 1, countRole(user, DeviceRole.COMPANION));
        assertEquals(attempts, deviceRepo.countByUserAndStatusIn(user, NON_REVOKED));
    }

    @Test
    void concurrentEnrollmentCannotExceedFiveDeviceTotal() throws Exception {
        User user = createUser("role_conctotal");
        LoginResponseDto login = login(user.getUsername());
        bootstrap(user, login, 810);
        logout(login.sessionId());
        for (int seed = 811; seed <= 814; seed++) {
            enrollAndRelease(user, seed);
        }
        assertEquals(5, deviceRepo.countByUserAndStatusIn(user, NON_REVOKED));

        int attempts = 2;
        List<UUID> sessions = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            sessions.add(login(user.getUsername()).sessionId());
        }
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CyclicBarrier barrier = new CyclicBarrier(attempts);
        AtomicInteger limitExceeded = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            final int seed = 820 + i;
            final UUID sessionId = sessions.get(i);
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                    deviceService.enrollDevice(user.getUserId(), sessionId,
                            E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
                    throw new AssertionError("Enrollment beyond the cap must not succeed");
                } catch (DeviceLimitExceededException e) {
                    limitExceeded.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError("Unexpected exception during concurrent over-cap enrollment", e);
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(attempts, limitExceeded.get());
        assertEquals(5, deviceRepo.countByUserAndStatusIn(user, NON_REVOKED));
        assertEquals(1, countRole(user, DeviceRole.PRIMARY));
        assertEquals(4, countRole(user, DeviceRole.COMPANION));
    }

    @Test
    void roleAppearsInExistingDeviceResponses() {
        User alice = createUser("role_dto");
        User bob = createUser("role_dto_bob");
        befriend(alice, bob);

        LoginResponseDto aliceLogin = login(alice.getUsername());
        EnrollDeviceResponseDto bootstrapped = bootstrap(alice, aliceLogin, 830);
        assertEquals(DeviceRole.PRIMARY, bootstrapped.getDevice().getDeviceRole());

        LoginResponseDto pendingLogin = login(alice.getUsername());
        EnrollDeviceResponseDto pending = deviceService.enrollDevice(
                alice.getUserId(), pendingLogin.sessionId(),
                E2eeTestKeys.enrollRequest(831, ClientPlatform.WEB));
        assertEquals(DeviceRole.COMPANION, pending.getDevice().getDeviceRole());

        DeviceListDto list = deviceService.listDevices(alice.getUserId());
        assertEquals(2, list.getDevices().size());
        assertTrue(list.getDevices().stream()
                .anyMatch(d -> d.getDeviceRole() == DeviceRole.PRIMARY));
        assertTrue(list.getDevices().stream()
                .anyMatch(d -> d.getDeviceRole() == DeviceRole.COMPANION));

        List<RecipientDeviceDto> directory =
                deviceService.getRecipientDevices(bob.getUserId(), alice.getUsername());
        assertEquals(1, directory.size());
        assertEquals(DeviceRole.PRIMARY, directory.get(0).getDeviceRole());

        deviceService.approveDevice(alice.getUserId(), aliceLogin.sessionId(),
                pending.getDevice().getDeviceId());
        List<RecipientDeviceDto> afterApproval =
                deviceService.getRecipientDevices(bob.getUserId(), alice.getUsername());
        assertEquals(2, afterApproval.size());
        assertTrue(afterApproval.stream()
                .anyMatch(d -> d.getDeviceRole() == DeviceRole.COMPANION));

        ClaimPrekeyResponseDto claimed = deviceService.claimOneTimePrekey(
                bob.getUserId(), bootstrapped.getDevice().getDeviceId(), UUID.randomUUID());
        assertEquals(DeviceRole.PRIMARY, claimed.getDeviceRole());
    }

    @Test
    void clientSuppliedRoleIsNotAcceptedAsAuthority() throws Exception {
        // The enrollment request DTO exposes no role property at all.
        boolean hasRoleProperty = false;
        for (var field : com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceRequestDto.class
                .getDeclaredFields()) {
            hasRoleProperty |= field.getName().equalsIgnoreCase("deviceRole")
                    || field.getName().equalsIgnoreCase("role");
        }
        assertFalse(hasRoleProperty, "EnrollDeviceRequestDto must not carry a role field");

        User user = createUser("role_noinject");
        LoginResponseDto login = login(user.getUsername());

        // Unknown "deviceRole" JSON is ignored: the first device is PRIMARY
        // even when the client asks for COMPANION.
        mockMvc.perform(post("/api/e2ee/devices")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollJson(840, "COMPANION")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.device.deviceRole").value("PRIMARY"));

        // And a later device is COMPANION even when the client asks for PRIMARY.
        LoginResponseDto second = login(user.getUsername());
        mockMvc.perform(post("/api/e2ee/devices")
                        .header("Authorization", "Bearer " + second.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollJson(841, "PRIMARY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.device.deviceRole").value("COMPANION"));

        assertEquals(1, countRole(user, DeviceRole.PRIMARY));
        assertEquals(1, countRole(user, DeviceRole.COMPANION));
    }

    @Test
    void approvalFromActiveCompanionRemainsAllowed() {
        User user = createUser("role_approve");
        LoginResponseDto primaryLogin = login(user.getUsername());
        bootstrap(user, primaryLogin, 850);

        LoginResponseDto loginA = login(user.getUsername());
        UUID deviceA = deviceService.enrollDevice(
                user.getUserId(), loginA.sessionId(), E2eeTestKeys.enrollRequest(851, ClientPlatform.WEB))
                .getDevice().getDeviceId();
        LoginResponseDto loginB = login(user.getUsername());
        UUID deviceB = deviceService.enrollDevice(
                user.getUserId(), loginB.sessionId(), E2eeTestKeys.enrollRequest(852, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        deviceService.approveDevice(user.getUserId(), primaryLogin.sessionId(), deviceA);
        // An ACTIVE COMPANION approves exactly like any trusted device did
        // before roles existed; approval was deliberately not made
        // Primary-only in this slice.
        DeviceDto approved = deviceService.approveDevice(user.getUserId(), loginA.sessionId(), deviceB);
        assertEquals(DeviceStatus.ACTIVE, approved.getStatus());
        assertEquals(DeviceRole.COMPANION, approved.getDeviceRole());
    }

    @Test
    void revocationBehaviorRemainsUnchanged() {
        User user = createUser("role_revoke");
        LoginResponseDto primaryLogin = login(user.getUsername());
        UUID primaryId = bootstrap(user, primaryLogin, 860).getDevice().getDeviceId();

        LoginResponseDto companionLogin = login(user.getUsername());
        UUID companionId = deviceService.enrollDevice(
                user.getUserId(), companionLogin.sessionId(),
                E2eeTestKeys.enrollRequest(861, ClientPlatform.WEB)).getDevice().getDeviceId();
        deviceService.approveDevice(user.getUserId(), primaryLogin.sessionId(), companionId);

        // A COMPANION-bound session revokes the PRIMARY: revocation was
        // deliberately not made Primary-only in this slice.
        deviceService.revokeDevice(user.getUserId(), primaryId);
        assertEquals(DeviceStatus.REVOKED, deviceRepo.findById(primaryId).orElseThrow().getStatus());
        assertEquals(DeviceRole.PRIMARY, deviceRepo.findById(primaryId).orElseThrow().getDeviceRole());
        assertNotNull(sessionRepo.findById(primaryLogin.sessionId()).orElseThrow().getRevokedAt());

        // The surviving COMPANION is untouched and still usable.
        E2eeDevice companion = deviceRepo.findById(companionId).orElseThrow();
        assertEquals(DeviceStatus.ACTIVE, companion.getStatus());
        assertEquals(DeviceRole.COMPANION, companion.getDeviceRole());

        // Revoking the COMPANION frees its slot for a new COMPANION.
        deviceService.revokeDevice(user.getUserId(), companionId);
        assertEquals(EnrollmentState.RECOVERY_REQUIRED,
                deviceService.listDevices(user.getUserId()).getEnrollmentState());
    }

    private String enrollJson(int seed, String requestedRole) {
        return """
                {
                  "registrationId": %d,
                  "deviceIdentityPublicKey": "%s",
                  "signedPrekeyId": %d,
                  "signedPrekey": "%s",
                  "signedPrekeySignature": "%s",
                  "kyberPrekeyId": %d,
                  "kyberPrekey": "%s",
                  "kyberPrekeySignature": "%s",
                  "clientPlatform": "WEB",
                  "clientName": "Test",
                  "clientVersion": "1.0.0",
                  "deviceRole": "%s"
                }
                """.formatted(
                1000 + seed,
                E2eeTestKeys.key(seed * 10 + 1),
                2000 + seed,
                E2eeTestKeys.key(seed * 10 + 2),
                E2eeTestKeys.key(seed * 10 + 3, 64),
                4000 + seed,
                E2eeTestKeys.key(seed * 10 + 4, 1569),
                E2eeTestKeys.key(seed * 10 + 5, 64),
                requestedRole);
    }
}
