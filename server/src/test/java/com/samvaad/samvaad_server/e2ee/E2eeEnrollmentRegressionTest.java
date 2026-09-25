package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.e2ee.device.DeviceStatus;
import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo;
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.RecoveryEnrollRequestDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceLimitExceededException;
import com.samvaad.samvaad_server.e2ee.exception.SessionAlreadyBoundException;
import com.samvaad.samvaad_server.e2ee.exception.SessionAlreadyBoundException;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * Regression tests for the audit fixes: no session rebinding on enrollment
 * (F1), explicit recovery device-cap enforcement (F2), and recovery request
 * validation with precise integrity-error mapping (F3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class E2eeEnrollmentRegressionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private E2eeDeviceService deviceService;

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
    private com.samvaad.samvaad_server.messaging.MessageRepo messageRepo;

    @Autowired
    private com.samvaad.samvaad_server.messaging.ConversationRepo conversationRepo;

    @Autowired
    private com.samvaad.samvaad_server.friendrequest.FriendRequestRepo friendRequestRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

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
    void boundSessionCannotEnrollAnotherDevice() {
        User user = createUser("e2ee_reg_rebind");
        LoginResponseDto login = login(user.getUsername());

        // 1. Device A through bootstrap; 2. session bound to A.
        UUID deviceA = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(500, ClientPlatform.WEB))
                .getDevice().getDeviceId();
        assertEquals(deviceA, sessionRepo.findById(login.sessionId()).orElseThrow().getDeviceId());

        // 3./4. Second enrollment from the same session is rejected with 409.
        assertThrows(SessionAlreadyBoundException.class, () -> deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(501, ClientPlatform.WEB)));

        // 5. Session remains bound to A; 6. no Device B exists.
        assertEquals(deviceA, sessionRepo.findById(login.sessionId()).orElseThrow().getDeviceId());
        assertEquals(1, deviceRepo.count());

        // 7. Device A remains ACTIVE and usable for its trusted operations.
        assertEquals(DeviceStatus.ACTIVE, deviceRepo.findById(deviceA).orElseThrow().getStatus());
        DeviceDto uploaded = deviceService.uploadOneTimePrekeys(
                user.getUserId(), login.sessionId(), deviceA, E2eeTestKeys.uploadBatch(1));
        assertEquals(100, uploaded.getAvailablePrekeys());
    }

    @Test
    void boundSessionEnrollRejectedOverHttpWith409() throws Exception {
        User user = createUser("e2ee_reg_rebind_http");
        LoginResponseDto login = login(user.getUsername());
        deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(510, ClientPlatform.WEB));

        mockMvc.perform(post("/api/e2ee/devices")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollJson(511)))
                .andExpect(status().isConflict());
        assertEquals(1, deviceRepo.count());
    }

    @Test
    void recoveryCannotExceedDeviceCap() {
        User user = createUser("e2ee_reg_reccap");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(520, ClientPlatform.WEB));
        String code = first.getRecoveryCodes().get(0);
        for (int seed = 521; seed <= 524; seed++) {
            LoginResponseDto fresh = login(user.getUsername());
            deviceService.enrollDevice(
                    user.getUserId(), fresh.sessionId(), E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
        }
        deviceService.revokeDevice(user.getUserId(), first.getDevice().getDeviceId());
        assertEquals(4, deviceRepo.countByUserAndStatusIn(user,
                java.util.EnumSet.of(DeviceStatus.PENDING, DeviceStatus.ACTIVE)));

        // Bypass the service cap to stage five non-revoked devices with zero
        // ACTIVE: the recovery path must still refuse a sixth.
        insertPendingDevice(user, 525);
        assertEquals(5, deviceRepo.countByUserAndStatusIn(user,
                java.util.EnumSet.of(DeviceStatus.PENDING, DeviceStatus.ACTIVE)));

        LoginResponseDto fresh = login(user.getUsername());
        RecoveryEnrollRequestDto request = new RecoveryEnrollRequestDto();
        request.setRecoveryCode(code);
        request.setDevice(E2eeTestKeys.enrollRequest(526, ClientPlatform.ANDROID));
        assertThrows(DeviceLimitExceededException.class, () ->
                deviceService.recoverEnroll(user.getUserId(), fresh.sessionId(), request));

        // Rejected recovery creates nothing and consumes nothing.
        assertEquals(5, deviceRepo.countByUserAndStatusIn(user,
                java.util.EnumSet.of(DeviceStatus.PENDING, DeviceStatus.ACTIVE)));
        assertEquals(E2eePolicy.RECOVERY_CODES_PER_SET,
                recoveryCodeRepo.findUsableByUser(user).size());

        // After freeing a slot, the same code succeeds.
        E2eeDevice staged = deviceRepo.findByUserUserIdOrderByCreatedAtAsc(user.getUserId()).stream()
                .filter(d -> d.getStatus() == DeviceStatus.PENDING
                        && d.getRegistrationId() == 1000 + 525)
                .findFirst().orElseThrow();
        deviceService.revokeDevice(user.getUserId(), staged.getDeviceId());
        DeviceDto recovered = deviceService.recoverEnroll(
                user.getUserId(), fresh.sessionId(), request);
        assertEquals(DeviceStatus.ACTIVE, recovered.getStatus());
    }

    @Test
    void recoveryWithMissingNestedDeviceFieldsIsBadRequest() throws Exception {
        User user = createUser("e2ee_reg_rec400");
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto first = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(530, ClientPlatform.WEB));
        String code = first.getRecoveryCodes().get(0);
        deviceService.revokeDevice(user.getUserId(), first.getDevice().getDeviceId());
        LoginResponseDto fresh = login(user.getUsername());
        int usableBefore = recoveryCodeRepo.findUsableByUser(user).size();

        // Null clientPlatform inside the nested device object.
        String body = """
                {
                  "recoveryCode": "%s",
                  "device": {
                    "registrationId": 1531,
                    "deviceIdentityPublicKey": "%s",
                    "signedPrekeyId": 2531,
                    "signedPrekey": "%s",
                    "signedPrekeySignature": "%s"
                  }
                }
                """.formatted(code,
                E2eeTestKeys.key(5311), E2eeTestKeys.key(5312), E2eeTestKeys.key(5313, 64));
        mockMvc.perform(post("/api/e2ee/recovery/enroll")
                        .header("Authorization", "Bearer " + fresh.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        assertEquals(1, deviceRepo.count());
        assertEquals(usableBefore, recoveryCodeRepo.findUsableByUser(user).size());

        // Missing nested device object entirely is also a 400.
        mockMvc.perform(post("/api/e2ee/recovery/enroll")
                        .header("Authorization", "Bearer " + fresh.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recoveryCode\": \"" + code + "\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(1, deviceRepo.count());
        assertEquals(usableBefore, recoveryCodeRepo.findUsableByUser(user).size());
    }

    @Test
    void nullBatchEntryIsBadRequestWithoutPersistence() throws Exception {
        User user = createUser("e2ee_reg_nullentry");
        LoginResponseDto login = login(user.getUsername());
        UUID deviceId = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(550, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        StringBuilder json = new StringBuilder("{\"prekeys\":[null");
        for (int i = 1; i < 100; i++) {
            json.append(",{\"prekeyId\":").append(i)
                    .append(",\"publicKey\":\"").append(E2eeTestKeys.key(9500 + i)).append("\"}");
        }
        mockMvc.perform(put("/api/e2ee/devices/" + deviceId + "/one-time-prekeys")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.append("]}").toString()))
                .andExpect(status().isBadRequest());

        assertEquals(0, prekeyRepo.count());
    }

    @Test
    void duplicateIdentityKeyStillConflictsOverHttp() throws Exception {        User user = createUser("e2ee_reg_dup409");
        LoginResponseDto login = login(user.getUsername());
        deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(540, ClientPlatform.WEB));

        LoginResponseDto fresh = login(user.getUsername());
        mockMvc.perform(post("/api/e2ee/devices")
                        .header("Authorization", "Bearer " + fresh.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollJson(540)))
                .andExpect(status().isConflict());
    }

    private void insertPendingDevice(User user, int seed) {
        E2eeDevice device = new E2eeDevice();
        device.setUser(user);
        device.setRegistrationId(1000 + seed);
        device.setDeviceIdentityPublicKey(E2eeMapper.decodeBase64("k", E2eeTestKeys.key(seed * 10 + 1)));
        device.setSignedPrekeyId(2000 + seed);
        device.setSignedPrekey(E2eeMapper.decodeBase64("k", E2eeTestKeys.key(seed * 10 + 2)));
        device.setSignedPrekeySignature(E2eeMapper.decodeBase64("k", E2eeTestKeys.key(seed * 10 + 3, 64)));
        device.setStatus(DeviceStatus.PENDING);
        device.setClientPlatform(ClientPlatform.WEB);
        device.setLastActiveAt(LocalDateTime.now());
        deviceRepo.save(device);
    }

    private String enrollJson(int seed) {
        return """
                {
                  "registrationId": %d,
                  "deviceIdentityPublicKey": "%s",
                  "signedPrekeyId": %d,
                  "signedPrekey": "%s",
                  "signedPrekeySignature": "%s",
                  "clientPlatform": "WEB",
                  "clientName": "Test",
                  "clientVersion": "1.0.0"
                }
                """.formatted(
                1000 + seed,
                E2eeTestKeys.key(seed * 10 + 1),
                2000 + seed,
                E2eeTestKeys.key(seed * 10 + 2),
                E2eeTestKeys.key(seed * 10 + 3, 64));
    }
}
