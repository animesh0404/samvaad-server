package com.samvaad.samvaad_server.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.e2ee.E2eeTestKeys;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.friendrequest.FriendRequestRepo;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.RevocationReason;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.session.SessionService;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * Device-level WebSocket connection over a real server port.
 *
 * <p>Proves one authenticated device opens exactly one kind of channel —
 * its own {@code /topic/devices/{deviceId}} — and cannot reach another
 * device's channel or any conversation-level destination. No application
 * message delivery exists in this slice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DeviceWebSocketIntegrationTest {

    private static final int TIMEOUT_SECONDS = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private E2eeDeviceService deviceService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private FriendRequestRepo friendRequestRepo;

    @Autowired
    private ConversationRepo conversationRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo deviceRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo oneTimePrekeyRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo recoveryCodeRepo;

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
        conversationRepo.deleteAll();
        friendRequestRepo.deleteAll();
        oneTimePrekeyRepo.deleteAll();
        recoveryCodeRepo.deleteAll();
        sessionRepo.deleteAll();
        deviceRepo.deleteAll();
        userProfileRepo.deleteAll();
        userRepo.deleteAll();
    }

    private static class RecordingHandler extends StompSessionHandlerAdapter {
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

        @Override
        public void handleException(StompSession session, StompCommand command,
                StompHeaders headers, byte[] payload, Throwable exception) {
            errors.offer(exception);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.offer(exception);
        }
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        user.setRole(UserRole.USER);
        return userRepo.save(user);
    }

    private LoginResponseDto login(User user) {
        return authenticationService.login(
                new LoginRequestDto(user.getUsername(), "secret123",
                        "inst-" + user.getUsername() + "-" + UUID.randomUUID(),
                        ClientPlatform.WEB, "Test Client", "1.0.0"),
                "127.0.0.1",
                "UserAgent");
    }

    private UUID enrollDevice(User user, LoginResponseDto login, int seed) {
        EnrollDeviceResponseDto response = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(),
                E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB));
        return response.getDevice().getDeviceId();
    }

    private static class ConnectedClient {
        final StompSession session;
        final RecordingHandler handler;

        ConnectedClient(StompSession session, RecordingHandler handler) {
            this.session = session;
            this.handler = handler;
        }
    }

    private ConnectedClient connect(String accessToken) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        StompHeaders headers = new StompHeaders();
        if (accessToken != null) {
            headers.set("Authorization", "Bearer " + accessToken);
        }
        RecordingHandler handler = new RecordingHandler();
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), headers, handler)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return new ConnectedClient(session, handler);
    }

    private Throwable subscribeError(ConnectedClient client, String destination) throws Exception {
        // Server ERROR frames are delivered to the session handler, not the
        // per-subscription handler.
        client.session.subscribe(destination, new StompSessionHandlerAdapter() {
        });
        return client.handler.errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void disconnectQuietly(StompSession session) {
        // The server closes the connection after a denied SUBSCRIBE, so a
        // session that received an ERROR frame may already be gone.
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    @Test
    void authenticatedDeviceSubscribesToOwnChannel() throws Exception {
        User alice = createUser("ws_alice");
        LoginResponseDto login = login(alice);
        UUID deviceId = enrollDevice(alice, login, 501);

        ConnectedClient client = connect(login.accessToken());
        try {
            Throwable error = subscribeError(client, "/topic/devices/" + deviceId);
            assertTrue(error == null, "own device channel must be subscribable, got: " + error);
        } finally {
            disconnectQuietly(client.session);
        }
    }

    @Test
    void deviceCannotSubscribeToAnotherDevicesChannel() throws Exception {
        User alice = createUser("ws_iso_a");
        User bob = createUser("ws_iso_b");
        LoginResponseDto aliceLogin = login(alice);
        LoginResponseDto bobLogin = login(bob);
        enrollDevice(alice, aliceLogin, 502);
        UUID bobDeviceId = enrollDevice(bob, bobLogin, 503);

        ConnectedClient client = connect(aliceLogin.accessToken());
        try {
            // Alice targets Bob's device channel: identical denial, no oracle.
            Throwable error = subscribeError(client, "/topic/devices/" + bobDeviceId);
            assertTrue(error != null, "cross-device subscription must be denied");
        } finally {
            disconnectQuietly(client.session);
        }
        // A denied SUBSCRIBE closes the connection server-side, so the
        // unknown-device probe uses a fresh connection.
        ConnectedClient retry = connect(aliceLogin.accessToken());
        try {
            Throwable unknown = subscribeError(retry, "/topic/devices/" + UUID.randomUUID());
            assertTrue(unknown != null, "unknown device subscription must be denied");
        } finally {
            disconnectQuietly(retry.session);
        }
    }

    @Test
    void conversationTopicSubscriptionIsDenied() throws Exception {
        User alice = createUser("ws_conv");
        LoginResponseDto login = login(alice);
        enrollDevice(alice, login, 504);

        ConnectedClient client = connect(login.accessToken());
        try {
            // The channel is device-level: conversation subscriptions do not exist.
            Throwable error = subscribeError(
                    client, "/topic/conversations/" + UUID.randomUUID());
            assertTrue(error != null, "conversation-level subscription must be denied");
        } finally {
            disconnectQuietly(client.session);
        }
    }

    @Test
    void connectWithoutJwtFails() {
        userService.createUser(new CreateUserRequestDto("ws_nojwt", "secret123", "ws_nojwt@example.com"));

        assertThrows(Exception.class, () -> connect(null));
    }

    @Test
    void revokingSessionTerminatesAllConnectionsForSession() throws Exception {
        User bob = createUser("ws_kick_multi");
        LoginResponseDto login = login(bob);
        UUID deviceId = enrollDevice(bob, login, 506);

        ConnectedClient first = connect(login.accessToken());
        ConnectedClient second = connect(login.accessToken());
        try {
            assertTrue(subscribeError(first, "/topic/devices/" + deviceId) == null);
            assertTrue(subscribeError(second, "/topic/devices/" + deviceId) == null);

            sessionService.revokeSession(login.sessionId(), RevocationReason.USER_LOGOUT);

            assertDisconnectedWithin(first, "first connection must terminate after revoke");
            assertDisconnectedWithin(second, "second connection must terminate after revoke");
        } finally {
            disconnectQuietly(first.session);
            disconnectQuietly(second.session);
        }
    }

    private static void assertDisconnectedWithin(ConnectedClient client, String context)
            throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (client.session.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(!client.session.isConnected(), context);
    }

    @Test
    void connectWithRevokedSessionFails() throws Exception {
        User alice = createUser("ws_revoked");
        LoginResponseDto login = login(alice);
        enrollDevice(alice, login, 505);
        sessionService.revokeSession(login.sessionId(), RevocationReason.USER_LOGOUT);

        assertThrows(Exception.class, () -> connect(login.accessToken()));
    }

    @Test
    void connectWithoutDeviceBindingFails() {
        User alice = createUser("ws_unbound");
        LoginResponseDto login = login(alice);

        assertThrows(Exception.class, () -> connect(login.accessToken()));
    }
}
