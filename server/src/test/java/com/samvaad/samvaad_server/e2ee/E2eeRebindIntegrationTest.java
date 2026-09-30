package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo;
import com.samvaad.samvaad_server.e2ee.dto.BindDeviceRequestDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.E2eeCiphertextItemDto;
import com.samvaad.samvaad_server.e2ee.dto.E2eeEnvelopeSubmitDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.SubmitE2eeMessageDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotActiveException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidRecoveryCodeException;
import com.samvaad.samvaad_server.e2ee.exception.SessionAlreadyBoundException;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.e2ee.message.E2eeEnvelopeRepo;
import com.samvaad.samvaad_server.e2ee.message.E2eeMailboxRepo;
import com.samvaad.samvaad_server.e2ee.message.E2eeMessageRepo;
import com.samvaad.samvaad_server.e2ee.message.E2eeMessageService;
import com.samvaad.samvaad_server.e2ee.message.E2eeSyncCursorRepo;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo;
import com.samvaad.samvaad_server.friendrequest.FriendRequestDto;
import com.samvaad.samvaad_server.friendrequest.FriendRequestRepo;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.messaging.ConversationRepo;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Existing-device recovery rebind ({@code POST
 * /api/e2ee/devices/{deviceId}/bind}): an unbound session joins an
 * already-enrolled ACTIVE device with one recovery code. No new device is
 * created, consumption and binding are atomic, and the rebound session
 * sees the device's pending mailbox.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class E2eeRebindIntegrationTest {

    @Autowired
    private E2eeDeviceService deviceService;

    @Autowired
    private E2eeMessageService messageService;

    @Autowired
    private FriendRequestService friendRequestService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserService userService;

    @Autowired
    private E2eeDeviceRepo deviceRepo;

    @Autowired
    private E2eeOneTimePrekeyRepo prekeyRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;


    @Autowired
    private ConversationRepo conversationRepo;

    @Autowired
    private FriendRequestRepo friendRequestRepo;

    @Autowired
    private E2eeMessageRepo e2eeMessageRepo;

    @Autowired
    private E2eeEnvelopeRepo e2eeEnvelopeRepo;

    @Autowired
    private E2eeMailboxRepo e2eeMailboxRepo;

    @Autowired
    private E2eeSyncCursorRepo e2eeSyncCursorRepo;

    @Autowired
    private E2eeRecoveryCodeRepo recoveryCodeRepo;

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

    private record Bootstrap(UUID deviceId, UUID sessionId, List<String> codes) {
    }

    private Bootstrap bootstrap(User user, int seed) {
        LoginResponseDto login = login(user.getUsername());
        EnrollDeviceResponseDto enrolled = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
        return new Bootstrap(enrolled.getDevice().getDeviceId(), login.sessionId(),
                enrolled.getRecoveryCodes());
    }

    private BindDeviceRequestDto bindRequest(String code) {
        BindDeviceRequestDto request = new BindDeviceRequestDto();
        request.setRecoveryCode(code);
        return request;
    }

    private UUID boundDeviceOf(UUID sessionId) {
        return sessionRepo.findById(sessionId).orElseThrow().getDeviceId();
    }

    private long deviceCount() {
        return deviceRepo.count();
    }

    private void befriend(User first, User second) {
        FriendRequestDto request = friendRequestService.sendRequest(first.getUserId(), second.getUsername());
        friendRequestService.acceptRequest(second.getUserId(), request.getRequestId());
    }

    @Test
    void rebindUnboundSessionToExistingDevice() {
        User bob = createUser("rebind_bob");
        Bootstrap first = bootstrap(bob, 100);
        long devicesBefore = deviceCount();
        UUID freshSession = login(bob.getUsername()).sessionId();
        assertNull(boundDeviceOf(freshSession));

        DeviceDto bound = deviceService.bindDevice(
                bob.getUserId(), freshSession, first.deviceId(), bindRequest(first.codes().get(0)));

        assertEquals(first.deviceId(), bound.getDeviceId());
        assertEquals(devicesBefore, deviceCount());
        assertEquals(first.deviceId(), boundDeviceOf(freshSession));
    }

    @Test
    void wrongCodeFailsWithoutSideEffectsAndStaysUsable() {
        User bob = createUser("rebind_wrong");
        Bootstrap first = bootstrap(bob, 101);
        UUID freshSession = login(bob.getUsername()).sessionId();

        assertThrows(InvalidRecoveryCodeException.class, () -> deviceService.bindDevice(
                bob.getUserId(), freshSession, first.deviceId(), bindRequest("WRONG-CODE-0000")));
        assertNull(boundDeviceOf(freshSession));
        assertEquals(1, deviceCount());

        // The rejected code consumed nothing: the real code still binds.
        DeviceDto bound = deviceService.bindDevice(
                bob.getUserId(), freshSession, first.deviceId(), bindRequest(first.codes().get(0)));
        assertEquals(first.deviceId(), bound.getDeviceId());
        assertEquals(first.deviceId(), boundDeviceOf(freshSession));
    }

    @Test
    void alreadyBoundSessionIsRejectedWithoutConsumingCode() {
        User bob = createUser("rebind_bound");
        Bootstrap first = bootstrap(bob, 102);

        assertThrows(SessionAlreadyBoundException.class, () -> deviceService.bindDevice(
                bob.getUserId(), first.sessionId(), first.deviceId(),
                bindRequest(first.codes().get(1))));
        assertEquals(first.deviceId(), boundDeviceOf(first.sessionId()));

        // Nothing was consumed: the code still binds a fresh session.
        UUID freshSession = login(bob.getUsername()).sessionId();
        DeviceDto bound = deviceService.bindDevice(
                bob.getUserId(), freshSession, first.deviceId(), bindRequest(first.codes().get(1)));
        assertEquals(first.deviceId(), bound.getDeviceId());
    }

    @Test
    void foreignDeviceIsRejected() {
        User alice = createUser("rebind_alice");
        User bob = createUser("rebind_bob2");
        Bootstrap aliceDevice = bootstrap(alice, 103);
        Bootstrap bobDevice = bootstrap(bob, 104);
        UUID bobSession = login(bob.getUsername()).sessionId();

        assertThrows(ForbiddenOperationException.class, () -> deviceService.bindDevice(
                bob.getUserId(), bobSession, aliceDevice.deviceId(),
                bindRequest(bobDevice.codes().get(0))));
        assertNull(boundDeviceOf(bobSession));
        assertEquals(aliceDevice.deviceId(),
                deviceRepo.findById(aliceDevice.deviceId()).orElseThrow().getDeviceId());
    }

    @Test
    void inactiveDeviceIsRejected() {
        User bob = createUser("rebind_inactive");
        Bootstrap first = bootstrap(bob, 105);
        deviceService.revokeDevice(bob.getUserId(), first.deviceId());
        UUID freshSession = login(bob.getUsername()).sessionId();

        assertThrows(DeviceNotActiveException.class, () -> deviceService.bindDevice(
                bob.getUserId(), freshSession, first.deviceId(), bindRequest(first.codes().get(0))));
        assertNull(boundDeviceOf(freshSession));
    }

    @Test
    void recoveryCodeIsSingleUse() {
        User bob = createUser("rebind_single");
        Bootstrap first = bootstrap(bob, 106);
        UUID secondSession = login(bob.getUsername()).sessionId();
        deviceService.bindDevice(
                bob.getUserId(), secondSession, first.deviceId(), bindRequest(first.codes().get(0)));

        UUID thirdSession = login(bob.getUsername()).sessionId();
        assertThrows(InvalidRecoveryCodeException.class, () -> deviceService.bindDevice(
                bob.getUserId(), thirdSession, first.deviceId(), bindRequest(first.codes().get(0))));
        assertNull(boundDeviceOf(thirdSession));
    }

    @Test
    void mailboxVisibleAfterRebind() {
        User alice = createUser("rebind_mb_alice");
        User bob = createUser("rebind_mb_bob");
        Bootstrap a1 = bootstrap(alice, 107);
        Bootstrap b1 = bootstrap(bob, 108);
        befriend(alice, bob);
        byte[] ciphertext = "opaque-bytes-rebind".getBytes();
        E2eeEnvelopeSubmitDto envelope = new E2eeEnvelopeSubmitDto();
        envelope.setSenderDeviceId(a1.deviceId());
        envelope.setRecipientDeviceId(b1.deviceId());
        envelope.setEnvelopeType("PREKEY_INIT");
        envelope.setCiphertext(Base64.getEncoder().encodeToString(ciphertext));
        SubmitE2eeMessageDto submit = new SubmitE2eeMessageDto();
        submit.setMessageRequestId(UUID.randomUUID());
        submit.setEnvelopes(List.of(envelope));
        messageService.submitMessage(alice.getUserId(), a1.sessionId(), submit);

        UUID freshSession = login(bob.getUsername()).sessionId();
        assertTrue(messageService.fetchMailbox(bob.getUserId(), freshSession, 50).isEmpty());

        deviceService.bindDevice(
                bob.getUserId(), freshSession, b1.deviceId(), bindRequest(b1.codes().get(0)));

        List<E2eeCiphertextItemDto> mailbox =
                messageService.fetchMailbox(bob.getUserId(), freshSession, 50);
        assertEquals(1, mailbox.size());
        assertEquals("PREKEY_INIT", mailbox.get(0).getEnvelopeType());
        assertEquals(
                Base64.getEncoder().encodeToString(ciphertext), mailbox.get(0).getCiphertext());
    }
}
