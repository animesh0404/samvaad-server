package com.samvaad.samvaad_server.e2ee.realtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.e2ee.E2eeTestKeys;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.dto.E2eeCiphertextItemDto;
import com.samvaad.samvaad_server.e2ee.dto.E2eeEnvelopeSubmitDto;
import com.samvaad.samvaad_server.e2ee.dto.SubmitE2eeMessageDto;
import com.samvaad.samvaad_server.e2ee.dto.SubmitE2eeMessageResponseDto;
import com.samvaad.samvaad_server.e2ee.exception.E2eeMessageConflictException;
import com.samvaad.samvaad_server.e2ee.message.E2eeMessageService;
import com.samvaad.samvaad_server.friendrequest.FriendRequestDto;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * E2EE realtime delivery over the device channel.
 *
 * <p>HTTPS submission persists first; only committed messages fan out,
 * one envelope per recipient device, to {@code /topic/devices/{id}}.
 * The mailbox remains the durable fallback and is never acknowledged
 * by realtime delivery.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class E2eeRealtimeDeliveryIntegrationTest {

    private static final int FRAME_TIMEOUT_SECONDS = 5;
    private static final int ABSENCE_TIMEOUT_SECONDS = 2;

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private E2eeMessageService messageService;

    @Autowired
    private E2eeDeviceService deviceService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private FriendRequestService friendRequestService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private com.samvaad.samvaad_server.session.SessionRepo sessionRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private com.samvaad.samvaad_server.friendrequest.FriendRequestRepo friendRequestRepo;

    @Autowired
    private com.samvaad.samvaad_server.messaging.ConversationRepo conversationRepo;

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

    private record Device(UUID deviceId, UUID sessionId, String accessToken) {
    }

    private static class FrameCollector implements StompFrameHandler {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            // Raw bytes: the server sends JSON, and byte[] conversion is
            // content-type independent.
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            frames.offer(new String((byte[]) payload, StandardCharsets.UTF_8));
        }
    }

    private static class SessionHandler extends StompSessionHandlerAdapter {
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

    private static class ConnectedClient {
        final StompSession session;
        final SessionHandler handler;

        ConnectedClient(StompSession session, SessionHandler handler) {
            this.session = session;
            this.handler = handler;
        }

        void close() {
            if (session.isConnected()) {
                session.disconnect();
            }
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

    private Device bootstrap(User user, int seed) {
        LoginResponseDto login = login(user);
        UUID deviceId = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(),
                E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB))
                .getDevice().getDeviceId();
        return new Device(deviceId, login.sessionId(), login.accessToken());
    }

    private Device secondDevice(User user, UUID trustedSessionId, int seed) {
        LoginResponseDto login = login(user);
        UUID pending = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(),
                E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB))
                .getDevice().getDeviceId();
        deviceService.approveDevice(user.getUserId(), trustedSessionId, pending);
        return new Device(pending, login.sessionId(), login.accessToken());
    }

    private void befriend(User first, User second) {
        FriendRequestDto request =
                friendRequestService.sendRequest(first.getUserId(), second.getUsername());
        friendRequestService.acceptRequest(second.getUserId(), request.getRequestId());
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private E2eeEnvelopeSubmitDto envelope(
            UUID sender, UUID recipient, String type, byte[] ciphertext) {
        E2eeEnvelopeSubmitDto dto = new E2eeEnvelopeSubmitDto();
        dto.setSenderDeviceId(sender);
        dto.setRecipientDeviceId(recipient);
        dto.setEnvelopeType(type);
        dto.setCiphertext(b64(ciphertext));
        return dto;
    }

    private SubmitE2eeMessageDto submit(UUID requestId, E2eeEnvelopeSubmitDto... envelopes) {
        SubmitE2eeMessageDto dto = new SubmitE2eeMessageDto();
        dto.setMessageRequestId(requestId);
        dto.setEnvelopes(List.of(envelopes));
        return dto;
    }

    private ConnectedClient connect(Device device) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new SimpleMessageConverter());
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + device.accessToken());
        SessionHandler handler = new SessionHandler();
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), headers, handler)
                .get(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return new ConnectedClient(session, handler);
    }

    private FrameCollector subscribeFrames(ConnectedClient client, UUID deviceId) throws Exception {
        // SUBSCRIBE is async and the in-memory broker offers no receipt for
        // broker destinations, so let the registration settle before a later
        // submit publishes; frame arrival itself is covered by polling reads.
        FrameCollector collector = new FrameCollector();
        client.session.subscribe("/topic/devices/" + deviceId, collector);
        Thread.sleep(1000);
        return collector;
    }

    private String receiveFrame(ConnectedClient client, FrameCollector collector) throws Exception {
        String frame = collector.frames.poll(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(frame != null,
                "expected a realtime event; session errors: " + client.handler.errors);
        return frame;
    }

    private void assertNoFrame(FrameCollector collector, String context) throws Exception {
        String frame = collector.frames.poll(ABSENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(frame == null, context + ", but received: " + frame);
    }

    private static void assertSafePayload(String frame, String expectedCiphertext) {
        assertTrue(frame.contains("\"ciphertext\":\"" + expectedCiphertext + "\""),
                "payload must carry the opaque ciphertext, got: " + frame);
        assertTrue(frame.contains("\"envelopeType\""), "payload must carry envelope type");
        assertTrue(frame.contains("\"messageId\""), "payload must carry message id");
        assertTrue(frame.contains("\"conversationId\""), "payload must carry conversation id");
        assertTrue(!frame.contains("\"content\""), "payload must not contain plaintext content");
        assertTrue(!frame.toLowerCase().contains("privatekey"),
                "payload must not contain private key material");
        assertTrue(!frame.toLowerCase().contains("plaintext"),
                "payload must not contain plaintext markers");
    }

    @Test
    void deliversOnlyEachDevicesOwnEnvelope() throws Exception {
        User alice = createUser("rt_alice");
        User bob = createUser("rt_bob");
        Device a1 = bootstrap(alice, 601);
        Device b1 = bootstrap(bob, 602);
        Device b2 = secondDevice(bob, b1.sessionId(), 603);
        befriend(alice, bob);
        ConnectedClient bobOne = connect(b1);
        ConnectedClient bobTwo = connect(b2);
        FrameCollector oneFrames = subscribeFrames(bobOne, b1.deviceId());
        FrameCollector twoFrames = subscribeFrames(bobTwo, b2.deviceId());
        try {
            byte[] ct1 = "ciphertext-envelope-for-bob-device-one-001".getBytes();
            byte[] ct2 = "ciphertext-envelope-for-bob-device-two-002".getBytes();
            SubmitE2eeMessageResponseDto response = messageService.submitMessage(
                    alice.getUserId(), a1.sessionId(),
                    submit(UUID.randomUUID(),
                            envelope(a1.deviceId(), b1.deviceId(), "PREKEY_INIT", ct1),
                            envelope(a1.deviceId(), b2.deviceId(), "PREKEY_INIT", ct2)));
            assertTrue(response.isCreatedNew());

            String frameOne = receiveFrame(bobOne, oneFrames);
            assertSafePayload(frameOne, b64(ct1));
            assertTrue(!frameOne.contains(b64(ct2)),
                    "device-1 must not receive device-2's envelope");

            String frameTwo = receiveFrame(bobTwo, twoFrames);
            assertSafePayload(frameTwo, b64(ct2));
            assertTrue(!frameTwo.contains(b64(ct1)),
                    "device-2 must not receive device-1's envelope");
        } finally {
            bobOne.close();
            bobTwo.close();
        }
    }

    @Test
    void offlineDeviceKeepsMailboxEntry() throws Exception {
        User alice = createUser("rt_off_a");
        User bob = createUser("rt_off_b");
        Device a1 = bootstrap(alice, 604);
        Device b1 = bootstrap(bob, 605);
        Device b2 = secondDevice(bob, b1.sessionId(), 606);
        befriend(alice, bob);
        ConnectedClient bobOne = connect(b1);
        FrameCollector oneFrames = subscribeFrames(bobOne, b1.deviceId());
        try {
            byte[] ct1 = "ciphertext-online-device-aaa".getBytes();
            byte[] ct2 = "ciphertext-offline-device-bbb".getBytes();
            messageService.submitMessage(
                    alice.getUserId(), a1.sessionId(),
                    submit(UUID.randomUUID(),
                            envelope(a1.deviceId(), b1.deviceId(), "RATCHET", ct1),
                            envelope(a1.deviceId(), b2.deviceId(), "RATCHET", ct2)));

            String frame = receiveFrame(bobOne, oneFrames);
            assertSafePayload(frame, b64(ct1));

            List<E2eeCiphertextItemDto> pending =
                    messageService.fetchMailbox(bob.getUserId(), b2.sessionId(), 50);
            assertEquals(1, pending.size());
            assertArrayEquals(ct2,
                    Base64.getDecoder().decode(pending.get(0).getCiphertext()));
        } finally {
            bobOne.close();
        }
    }

    @Test
    void oneChannelServesMultipleConversations() throws Exception {
        User alice = createUser("rt_mc_a");
        User bob = createUser("rt_mc_b");
        User carol = createUser("rt_mc_c");
        Device a1 = bootstrap(alice, 607);
        Device b1 = bootstrap(bob, 608);
        Device c1 = bootstrap(carol, 609);
        befriend(alice, bob);
        befriend(carol, bob);
        ConnectedClient bobClient = connect(b1);
        FrameCollector bobFrames = subscribeFrames(bobClient, b1.deviceId());
        try {
            SubmitE2eeMessageResponseDto first = messageService.submitMessage(
                    alice.getUserId(), a1.sessionId(),
                    submit(UUID.randomUUID(),
                            envelope(a1.deviceId(), b1.deviceId(), "RATCHET",
                                    "conv-one-ciphertext".getBytes())));
            SubmitE2eeMessageResponseDto second = messageService.submitMessage(
                    carol.getUserId(), c1.sessionId(),
                    submit(UUID.randomUUID(),
                            envelope(c1.deviceId(), b1.deviceId(), "RATCHET",
                                    "conv-two-ciphertext".getBytes())));

            String frameOne = receiveFrame(bobClient, bobFrames);
            String frameTwo = receiveFrame(bobClient, bobFrames);
            assertTrue(frameOne.contains(first.getConversationId().toString()));
            assertTrue(frameTwo.contains(second.getConversationId().toString()));
            assertTrue(!first.getConversationId().equals(second.getConversationId()));
        } finally {
            bobClient.close();
        }
    }

    @Test
    void reconnectRecoversUnacknowledgedMailboxItem() throws Exception {
        User alice = createUser("rt_rc_a");
        User bob = createUser("rt_rc_b");
        Device a1 = bootstrap(alice, 610);
        Device b1 = bootstrap(bob, 611);
        befriend(alice, bob);

        ConnectedClient firstConnection = connect(b1);
        FrameCollector firstFrames = subscribeFrames(firstConnection, b1.deviceId());
        byte[] ct = "ciphertext-reconnect-ccc".getBytes();
        SubmitE2eeMessageResponseDto response = messageService.submitMessage(
                alice.getUserId(), a1.sessionId(),
                submit(UUID.randomUUID(),
                        envelope(a1.deviceId(), b1.deviceId(), "RATCHET", ct)));
        try {
            String frame = receiveFrame(firstConnection, firstFrames);
            assertSafePayload(frame, b64(ct));
            // Disconnect before mailbox ACK: nothing is acknowledged by delivery.
        } finally {
            firstConnection.close();
        }

        List<E2eeCiphertextItemDto> pending =
                messageService.fetchMailbox(bob.getUserId(), b1.sessionId(), 50);
        assertEquals(1, pending.size());
        assertEquals(response.getMessageId(), pending.get(0).getMessageId());

        ConnectedClient secondConnection = connect(b1);
        try {
            secondConnection.close();
        } finally {
            secondConnection.close();
        }
        assertEquals(1, messageService.acknowledge(
                bob.getUserId(), b1.sessionId(), List.of(response.getMessageId()))
                .getAcknowledged());
        assertTrue(messageService.fetchMailbox(bob.getUserId(), b1.sessionId(), 50).isEmpty());
    }

    @Test
    void duplicateSubmitDeliversOnlyOnce() throws Exception {
        User alice = createUser("rt_dup_a");
        User bob = createUser("rt_dup_b");
        Device a1 = bootstrap(alice, 612);
        Device b1 = bootstrap(bob, 613);
        befriend(alice, bob);
        ConnectedClient bobClient = connect(b1);
        FrameCollector bobFrames = subscribeFrames(bobClient, b1.deviceId());
        try {
            UUID requestId = UUID.randomUUID();
            byte[] ct = "ciphertext-duplicate-ddd".getBytes();
            SubmitE2eeMessageResponseDto created = messageService.submitMessage(
                    alice.getUserId(), a1.sessionId(),
                    submit(requestId,
                            envelope(a1.deviceId(), b1.deviceId(), "RATCHET", ct)));
            SubmitE2eeMessageResponseDto replay = messageService.submitMessage(
                    alice.getUserId(), a1.sessionId(),
                    submit(requestId,
                            envelope(a1.deviceId(), b1.deviceId(), "RATCHET", ct)));
            assertTrue(created.isCreatedNew());
            assertTrue(!replay.isCreatedNew());

            receiveFrame(bobClient, bobFrames);
            assertNoFrame(bobFrames, "duplicate submit must not deliver a second event");
        } finally {
            bobClient.close();
        }
    }

    @Test
    void concurrentDuplicateSubmitDeliversOnlyOnce() throws Exception {
        User alice = createUser("rt_conc_a");
        User bob = createUser("rt_conc_b");
        Device a1 = bootstrap(alice, 620);
        Device b1 = bootstrap(bob, 621);
        Device b2 = secondDevice(bob, b1.sessionId(), 622);
        befriend(alice, bob);
        ConnectedClient bobOne = connect(b1);
        ConnectedClient bobTwo = connect(b2);
        FrameCollector oneFrames = subscribeFrames(bobOne, b1.deviceId());
        FrameCollector twoFrames = subscribeFrames(bobTwo, b2.deviceId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            UUID requestId = UUID.randomUUID();
            byte[] ct1 = "ciphertext-concurrent-one-ggg".getBytes();
            byte[] ct2 = "ciphertext-concurrent-two-hhh".getBytes();
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicReference<SubmitE2eeMessageResponseDto> first = new AtomicReference<>();
            AtomicReference<SubmitE2eeMessageResponseDto> second = new AtomicReference<>();
            Future<?> a = executor.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                first.set(messageService.submitMessage(alice.getUserId(), a1.sessionId(),
                        submit(requestId,
                                envelope(a1.deviceId(), b1.deviceId(), "RATCHET", ct1),
                                envelope(a1.deviceId(), b2.deviceId(), "RATCHET", ct2))));
                return null;
            });
            Future<?> b = executor.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                second.set(messageService.submitMessage(alice.getUserId(), a1.sessionId(),
                        submit(requestId,
                                envelope(a1.deviceId(), b1.deviceId(), "RATCHET", ct1),
                                envelope(a1.deviceId(), b2.deviceId(), "RATCHET", ct2))));
                return null;
            });
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);

            // Exactly one persistence winner and one creation response.
            assertEquals(first.get().getMessageId(), second.get().getMessageId());
            assertTrue(first.get().isCreatedNew() != second.get().isCreatedNew(),
                    "exactly one submission must win creation");
            assertEquals(1, e2eeMessageRepo.findAll().size());
            assertEquals(1, e2eeMailboxRepo.countByRecipientDeviceId(b1.deviceId()));
            assertEquals(1, e2eeMailboxRepo.countByRecipientDeviceId(b2.deviceId()));

            // Exactly one realtime frame per device channel, each carrying
            // only its own envelope; the loser replays silently.
            String frameOne = receiveFrame(bobOne, oneFrames);
            assertSafePayload(frameOne, b64(ct1));
            assertTrue(!frameOne.contains(b64(ct2)),
                    "device-1 must not receive device-2's envelope");
            String frameTwo = receiveFrame(bobTwo, twoFrames);
            assertSafePayload(frameTwo, b64(ct2));
            assertTrue(!frameTwo.contains(b64(ct1)),
                    "device-2 must not receive device-1's envelope");
            assertNoFrame(oneFrames, "concurrent duplicate must not deliver a second event");
            assertNoFrame(twoFrames, "concurrent duplicate must not deliver a second event");
        } finally {
            executor.shutdown();
            bobOne.close();
            bobTwo.close();
        }
    }

    @Test
    void failedSubmitProducesNoRealtimeEvent() throws Exception {
        User alice = createUser("rt_fail_a");
        User bob = createUser("rt_fail_b");
        Device a1 = bootstrap(alice, 614);
        Device b1 = bootstrap(bob, 615);
        befriend(alice, bob);
        ConnectedClient bobClient = connect(b1);
        FrameCollector failFrames = subscribeFrames(bobClient, b1.deviceId());
        try {
            UUID requestId = UUID.randomUUID();
            messageService.submitMessage(
                    alice.getUserId(), a1.sessionId(),
                    submit(requestId,
                            envelope(a1.deviceId(), b1.deviceId(), "RATCHET",
                                    "original-ciphertext".getBytes())));
            receiveFrame(bobClient, failFrames);

            // Same requestId with different content: conflict, rollback, no event.
            assertThrows(E2eeMessageConflictException.class, () -> messageService.submitMessage(
                    alice.getUserId(), a1.sessionId(),
                    submit(requestId,
                            envelope(a1.deviceId(), b1.deviceId(), "RATCHET",
                                    "tampered-ciphertext".getBytes()))));
            assertNoFrame(failFrames, "rolled-back submit must not deliver an event");
        } finally {
            bobClient.close();
        }
    }

    @Test
    void httpSubmitDeliversRealtimeEvent() throws Exception {
        User alice = createUser("rt_http_a");
        User bob = createUser("rt_http_b");
        Device a1 = bootstrap(alice, 616);
        Device b1 = bootstrap(bob, 617);
        befriend(alice, bob);
        ConnectedClient bobClient = connect(b1);
        FrameCollector httpFrames = subscribeFrames(bobClient, b1.deviceId());
        try {
            byte[] ct = "ciphertext-http-submit-eee".getBytes();
            String body = """
                    {"messageRequestId":"%s","envelopes":[{
                    "senderDeviceId":"%s","recipientDeviceId":"%s",
                    "envelopeType":"PREKEY_INIT","ciphertext":"%s"}]}
                    """.formatted(UUID.randomUUID(), a1.deviceId(), b1.deviceId(), b64(ct));

            mockMvc.perform(post("/api/e2ee/messages")
                            .header("Authorization", "Bearer " + a1.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.createdNew").value(true));

            String frame = receiveFrame(bobClient, httpFrames);
            assertSafePayload(frame, b64(ct));
        } finally {
            bobClient.close();
        }
    }

    @Test
    void senderReceivesNothingOnOwnChannel() throws Exception {
        User alice = createUser("rt_self_a");
        User bob = createUser("rt_self_b");
        Device a1 = bootstrap(alice, 618);
        Device b1 = bootstrap(bob, 619);
        befriend(alice, bob);
        ConnectedClient aliceClient = connect(a1);
        FrameCollector aliceFrames = subscribeFrames(aliceClient, a1.deviceId());
        try {
            messageService.submitMessage(
                    alice.getUserId(), a1.sessionId(),
                    submit(UUID.randomUUID(),
                            envelope(a1.deviceId(), b1.deviceId(), "RATCHET",
                                    "not-for-sender-fff".getBytes())));
            assertNoFrame(aliceFrames, "sender must not receive events on its own channel");
        } finally {
            aliceClient.close();
        }
    }
}
