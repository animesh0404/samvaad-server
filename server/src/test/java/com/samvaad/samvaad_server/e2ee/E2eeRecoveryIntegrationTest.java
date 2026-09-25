package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

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
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.RecoveryEnrollRequestDto;
import com.samvaad.samvaad_server.e2ee.dto.RotateRecoveryCodesResponseDto;
import com.samvaad.samvaad_server.e2ee.exception.InvalidRecoveryCodeException;
import com.samvaad.samvaad_server.e2ee.exception.RecoveryRequiredException;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCode;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * RECOVERY_REQUIRED state and atomic recovery-code consumption: wrong codes
 * fail without side effects, a correct code creates exactly one new ACTIVE
 * device with a new identity, codes are single-use, and rotation retires the
 * prior set.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class E2eeRecoveryIntegrationTest {

    @Autowired
    private E2eeDeviceService deviceService;

    @Autowired
    private E2eeRecoveryService recoveryService;

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

    private RecoveryEnrollRequestDto recoveryRequest(String code, int seed) {
        RecoveryEnrollRequestDto request = new RecoveryEnrollRequestDto();
        request.setRecoveryCode(code);
        request.setDevice(E2eeTestKeys.enrollRequest(seed, ClientPlatform.ANDROID));
        return request;
    }

    @Test
    void zeroActiveDevicesRequireRecoveryNotBootstrap() {
        User user = createUser("e2ee_rec");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(200, ClientPlatform.WEB));
        deviceService.revokeDevice(user.getUserId(), first.getDevice().getDeviceId());

        // Plain enrollment is rejected even though credentials are valid.
        // Revocation killed the enrolling session, so a fresh session is used.
        LoginResponseDto fresh = login(user.getUsername());
        assertThrows(RecoveryRequiredException.class, () -> deviceService.enrollDevice(
                user.getUserId(), fresh.sessionId(), E2eeTestKeys.enrollRequest(201, ClientPlatform.WEB)));
    }

    @Test
    void wrongCodeFailsWithoutSideEffects() {
        User user = createUser("e2ee_rec_wrong");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(210, ClientPlatform.WEB));
        deviceService.revokeDevice(user.getUserId(), first.getDevice().getDeviceId());
        int usableBefore = recoveryCodeRepo.findUsableByUser(user).size();

        LoginResponseDto fresh = login(user.getUsername());
        assertThrows(InvalidRecoveryCodeException.class, () -> deviceService.recoverEnroll(
                user.getUserId(), fresh.sessionId(), recoveryRequest("not-a-real-code", 211)));

        assertEquals(1, deviceRepo.count());
        assertEquals(usableBefore, recoveryCodeRepo.findUsableByUser(user).size());
    }

    @Test
    void correctCodeCreatesNewActiveDeviceAndIsSingleUse() {
        User user = createUser("e2ee_rec_ok");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(220, ClientPlatform.WEB));
        String code = first.getRecoveryCodes().get(0);
        UUID oldDeviceId = first.getDevice().getDeviceId();
        deviceService.revokeDevice(user.getUserId(), oldDeviceId);

        LoginResponseDto fresh = login(user.getUsername());
        DeviceDto recovered = deviceService.recoverEnroll(
                user.getUserId(), fresh.sessionId(), recoveryRequest(code, 221));

        assertEquals(DeviceStatus.ACTIVE, recovered.getStatus());
        assertNotEquals(oldDeviceId, recovered.getDeviceId());
        assertEquals(recovered.getDeviceId(),
                sessionRepo.findById(fresh.sessionId()).orElseThrow().getDeviceId());

        // The same code cannot be used again. A fresh session is required
        // because the successful recovery bound the previous one.
        LoginResponseDto retry = login(user.getUsername());
        assertThrows(InvalidRecoveryCodeException.class, () -> deviceService.recoverEnroll(
                user.getUserId(), retry.sessionId(), recoveryRequest(code, 222)));
        assertEquals(2, deviceRepo.count());
    }

    @Test
    void recoveryCodeUnusableWhileActiveDevicesExist() {
        User user = createUser("e2ee_rec_active");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(230, ClientPlatform.WEB));
        String code = first.getRecoveryCodes().get(3);
        int usableBefore = recoveryCodeRepo.findUsableByUser(user).size();

        // Valid code, wrong state: rejected and NOT consumed. A fresh
        // unbound session is used so the attempt reaches the state check
        // rather than the session-binding guard.
        LoginResponseDto fresh = login(user.getUsername());
        assertThrows(InvalidRecoveryCodeException.class, () -> deviceService.recoverEnroll(
                user.getUserId(), fresh.sessionId(), recoveryRequest(code, 231)));
        assertEquals(usableBefore, recoveryCodeRepo.findUsableByUser(user).size());
        assertEquals(1, deviceRepo.count());
    }

    @Test
    void rotationRetiresPriorSet() {
        User user = createUser("e2ee_rec_rot");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(240, ClientPlatform.WEB));
        String oldCode = first.getRecoveryCodes().get(5);

        RotateRecoveryCodesResponseDto rotated =
                deviceService.rotateRecoveryCodes(user.getUserId(), login.sessionId());
        assertNotNull(rotated.getSetId());
        assertEquals(E2eePolicy.RECOVERY_CODES_PER_SET, rotated.getRecoveryCodes().size());

        // Old set is dead even though the code was never consumed.
        deviceService.revokeDevice(user.getUserId(), first.getDevice().getDeviceId());
        LoginResponseDto fresh = login(user.getUsername());
        assertThrows(InvalidRecoveryCodeException.class, () -> deviceService.recoverEnroll(
                user.getUserId(), fresh.sessionId(), recoveryRequest(oldCode, 241)));

        DeviceDto recovered = deviceService.recoverEnroll(
                user.getUserId(), fresh.sessionId(), recoveryRequest(rotated.getRecoveryCodes().get(0), 242));
        assertEquals(DeviceStatus.ACTIVE, recovered.getStatus());
    }

    @Test
    void plaintextCodesNeverPersisted() {
        User user = createUser("e2ee_rec_plain");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(250, ClientPlatform.WEB));

        List<E2eeRecoveryCode> stored = recoveryCodeRepo.findUsableByUser(user);
        assertEquals(E2eePolicy.RECOVERY_CODES_PER_SET, stored.size());
        for (E2eeRecoveryCode code : stored) {
            assertTrue(code.getCodeHash().startsWith("$2"));
            for (String plain : first.getRecoveryCodes()) {
                assertNotEquals(plain, code.getCodeHash());
            }
        }
        assertEquals(25, first.getRecoveryCodes().stream().distinct().count());
    }

    @Test
    void unboundSessionCannotRotateCodes() {
        User user = createUser("e2ee_rec_rotno");
        LoginResponseDto trustedLogin = login(user.getUsername());
        deviceService.enrollDevice(
                user.getUserId(), trustedLogin.sessionId(), E2eeTestKeys.enrollRequest(260, ClientPlatform.WEB));

        LoginResponseDto unbound = login(user.getUsername());
        assertThrows(com.samvaad.samvaad_server.exception.ForbiddenOperationException.class,
                () -> deviceService.rotateRecoveryCodes(user.getUserId(), unbound.sessionId()));
        assertEquals(E2eePolicy.RECOVERY_CODES_PER_SET,
                recoveryCodeRepo.findUsableByUser(user).size());
    }

    @Test
    void recoveryServiceGeneratesUsableSetDirectly() {
        User user = createUser("e2ee_rec_unit");
        List<String> codes = recoveryService.createInitialSet(user);
        assertEquals(E2eePolicy.RECOVERY_CODES_PER_SET, codes.size());
        recoveryService.consumeCode(user, codes.get(0));
        assertEquals(E2eePolicy.RECOVERY_CODES_PER_SET - 1,
                recoveryCodeRepo.findUsableByUser(user).size());
    }
}
