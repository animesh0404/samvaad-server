package com.samvaad.samvaad_server.e2ee;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.exception.RecoveryRequiredException;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * HTTP boundary for the E2EE foundation: authentication, ownership,
 * friendship gating, recovery-required semantics, revocation invalidation,
 * and the absence of any session-to-existing-device binding route.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class E2eeSecurityIntegrationTest {

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

    private String bearer(LoginResponseDto login) {
        return "Bearer " + login.accessToken();
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

    private String uploadJson(int firstPrekeyId, int size) {
        StringBuilder json = new StringBuilder("{\"prekeys\":[");
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"prekeyId\":").append(firstPrekeyId + i)
                    .append(",\"publicKey\":\"").append(E2eeTestKeys.key(9000 + firstPrekeyId + i))
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    @Test
    void unauthenticatedDeviceRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/e2ee/devices"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/e2ee/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bootstrapOverHttpReturnsCodesOnce() throws Exception {
        User user = createUser("e2ee_http_boot");
        LoginResponseDto login = login(user.getUsername());

        mockMvc.perform(post("/api/e2ee/devices")
                        .header("Authorization", bearer(login))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollJson(400)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.device.status").value("ACTIVE"))
                .andExpect(jsonPath("$.enrollmentState").value("NEVER_ENROLLED"))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(25));

        mockMvc.perform(get("/api/e2ee/devices")
                        .header("Authorization", bearer(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentState").value("ENROLLED_ACTIVE"))
                .andExpect(jsonPath("$.devices.length()").value(1))
                .andExpect(jsonPath("$.devices[0].availablePrekeys").value(0));
    }

    @Test
    void foreignDeviceOperationsAreDenied() throws Exception {
        User alice = createUser("e2ee_http_alice");
        User bob = createUser("e2ee_http_bob");
        LoginResponseDto aliceLogin = login(alice.getUsername());
        LoginResponseDto bobLogin = login(bob.getUsername());
        UUID aliceDevice = deviceService.enrollDevice(
                alice.getUserId(), aliceLogin.sessionId(), E2eeTestKeys.enrollRequest(410, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        mockMvc.perform(post("/api/e2ee/devices/" + aliceDevice + "/approve")
                        .header("Authorization", bearer(bobLogin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/e2ee/devices/" + aliceDevice + "/one-time-prekeys")
                        .header("Authorization", bearer(bobLogin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadJson(1, 100)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/e2ee/devices/" + aliceDevice)
                        .header("Authorization", bearer(bobLogin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/e2ee/devices/" + UUID.randomUUID() + "/approve")
                        .header("Authorization", bearer(bobLogin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void directoryAndClaimAreFriendshipGated() throws Exception {
        User alice = createUser("e2ee_http_dir");
        User stranger = createUser("e2ee_http_dir_stranger");
        LoginResponseDto aliceLogin = login(alice.getUsername());
        LoginResponseDto strangerLogin = login(stranger.getUsername());
        UUID aliceDevice = deviceService.enrollDevice(
                alice.getUserId(), aliceLogin.sessionId(), E2eeTestKeys.enrollRequest(420, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        mockMvc.perform(get("/api/e2ee/users/" + alice.getUsername() + "/devices")
                        .header("Authorization", bearer(strangerLogin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/e2ee/devices/" + aliceDevice + "/one-time-prekeys/claim")
                        .header("Authorization", bearer(strangerLogin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/e2ee/users/no_such_user/devices")
                        .header("Authorization", bearer(strangerLogin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void noSessionBindingRouteExists() throws Exception {
        User user = createUser("e2ee_http_bind");
        LoginResponseDto login = login(user.getUsername());
        UUID deviceId = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(430, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        // No API may bind an existing session to an already-existing device.
        mockMvc.perform(post("/api/e2ee/devices/" + deviceId + "/bind-session")
                        .header("Authorization", bearer(login))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unboundSessionCannotApprove() throws Exception {
        User user = createUser("e2ee_http_approve");
        LoginResponseDto trustedLogin = login(user.getUsername());
        deviceService.enrollDevice(
                user.getUserId(), trustedLogin.sessionId(), E2eeTestKeys.enrollRequest(440, ClientPlatform.WEB));

        LoginResponseDto pendingLogin = login(user.getUsername());
        EnrollDeviceResponseDto pending = deviceService.enrollDevice(
                user.getUserId(), pendingLogin.sessionId(), E2eeTestKeys.enrollRequest(441, ClientPlatform.WEB));

        LoginResponseDto unbound = login(user.getUsername());
        mockMvc.perform(post("/api/e2ee/devices/" + pending.getDevice().getDeviceId() + "/approve")
                        .header("Authorization", bearer(unbound)))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedDeviceSessionIsRejected() throws Exception {
        User user = createUser("e2ee_http_revoke");
        LoginResponseDto login = login(user.getUsername());
        UUID deviceId = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(450, ClientPlatform.WEB))
                .getDevice().getDeviceId();

        mockMvc.perform(delete("/api/e2ee/devices/" + deviceId)
                        .header("Authorization", bearer(login)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/e2ee/devices")
                        .header("Authorization", bearer(login)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void recoveryRequiredCarriesStableReason() throws Exception {
        User user = createUser("e2ee_http_rec");
        LoginResponseDto login = login(user.getUsername());
        UUID deviceId = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(460, ClientPlatform.WEB))
                .getDevice().getDeviceId();
        deviceService.revokeDevice(user.getUserId(), deviceId);

        // Revocation killed the enrolling session, so recovery state is
        // observed through a fresh ordinary session.
        LoginResponseDto fresh = login(user.getUsername());
        mockMvc.perform(post("/api/e2ee/devices")
                        .header("Authorization", bearer(fresh))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollJson(461)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(RecoveryRequiredException.REASON));
    }

    @Test
    void validationFailuresAreBadRequest() throws Exception {
        User user = createUser("e2ee_http_bad");
        LoginResponseDto login = login(user.getUsername());

        mockMvc.perform(post("/api/e2ee/devices")
                        .header("Authorization", bearer(login))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationId\": 1}"))
                .andExpect(status().isBadRequest());

        UUID deviceId = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(470, ClientPlatform.WEB))
                .getDevice().getDeviceId();
        mockMvc.perform(put("/api/e2ee/devices/" + deviceId + "/one-time-prekeys")
                        .header("Authorization", bearer(login))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadJson(1, 10)))
                .andExpect(status().isBadRequest());
    }
}
