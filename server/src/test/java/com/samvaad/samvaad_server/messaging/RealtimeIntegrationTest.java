package com.samvaad.samvaad_server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.friendrequest.FriendRequestRepo;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RealtimeIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10L;

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthenticationService authenticationService;

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
    private MessageRepo messageRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        messageRepo.deleteAll();
        conversationRepo.deleteAll();
        friendRequestRepo.deleteAll();
        sessionRepo.deleteAll();
        userProfileRepo.deleteAll();
        userRepo.deleteAll();
    }

    private static class TestSessionHandler extends StompSessionHandlerAdapter {
        final BlockingQueue<MessageDto> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final CompletableFuture<StompSession> connected = new CompletableFuture<>();

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            connected.complete(session);
        }

        @Override
        public void handleException(StompSession session, StompCommand command,
                StompHeaders headers, byte[] payload, Throwable exception) {
            errors.offer(new String(payload));
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            connected.completeExceptionally(exception);
            errors.offer("transport:" + exception.getMessage());
        }
    }

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        return client;
    }

    private ConnectedClient connectClient(String accessToken) {
        TestSessionHandler handler = new TestSessionHandler();
        WebSocketStompClient client = stompClient();
        StompHeaders headers = new StompHeaders();
        if (accessToken != null) {
            headers.add("Authorization", "Bearer " + accessToken);
        }
        CompletableFuture<StompSession> future = client.connectAsync(
                "ws://localhost:" + port + "/ws",
                new org.springframework.web.socket.WebSocketHttpHeaders(),
                headers,
                handler);
        return new ConnectedClient(client, handler, future);
    }

    private record ConnectedClient(
            WebSocketStompClient client,
            TestSessionHandler handler,
            CompletableFuture<StompSession> connectedFuture) {
        StompSession session() throws Exception {
            return connectedFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        void close() {
            client.stop();
        }
    }

    private void subscribe(ConnectedClient client, String destination) throws Exception {
        client.session().subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return MessageDto.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                client.handler().messages.offer((MessageDto) payload);
            }
        });
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        user.setRole(UserRole.USER);
        return userRepo.save(user);
    }

    private LoginResponseDto loginAs(User user) {
        return authenticationService.login(
                new LoginRequestDto(
                        user.getUsername(),
                        "secret123",
                        "inst-" + user.getUsername(),
                        ClientPlatform.WEB,
                        "Test Client",
                        "1.0.0"),
                "127.0.0.1",
                "UserAgent");
    }

    private void befriend(User first, User second) {
        String token = loginAs(first).accessToken();
        String otherToken = loginAs(second).accessToken();

        String requestId;
        try {
            requestId = mockMvc.perform(post("/api/friend-requests")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"%s"}
                                    """.formatted(second.getUsername())))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString()
                    .split("\"requestId\":\"")[1].split("\"")[0];
            mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId)
                            .header("Authorization", "Bearer " + otherToken))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID sendHttpMessage(String token, String username, String content) {
        try {
            String body = mockMvc.perform(post("/api/conversations/direct/messages")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"%s","content":"%s","requestId":"%s"}
                                    """.formatted(username, content, UUID.randomUUID())))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return UUID.fromString(body.split("\"conversationId\":\"")[1].split("\"")[0]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void logout(String accessToken) {
        try {
            mockMvc.perform(post("/api/auth/logout")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isNoContent());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ChatSendRequest chatSend(UUID conversationId, String content, UUID requestId) {
        ChatSendRequest request = new ChatSendRequest();
        request.setConversationId(conversationId);
        request.setContent(content);
        request.setRequestId(requestId);
        return request;
    }

    @Test
    void authenticatedConnectSucceeds() throws Exception {
        User alice = createUser("rt_conn_a");
        String token = loginAs(alice).accessToken();
        ConnectedClient client = connectClient(token);
        try {
            StompSession session = client.session();
            assertTrue(session.isConnected());
        } finally {
            client.close();
        }
    }

    @Test
    void connectSucceedsWithoutInstallationIdentity() throws Exception {
        User alice = createUser("rt_conn_no_inst");
        String token = authenticationService.login(
                new LoginRequestDto(
                        alice.getUsername(),
                        "secret123",
                        null,
                        ClientPlatform.WEB,
                        "Test Client",
                        "1.0.0"),
                "127.0.0.1",
                "UserAgent").accessToken();
        ConnectedClient client = connectClient(token);
        try {
            StompSession session = client.session();
            assertTrue(session.isConnected());
        } finally {
            client.close();
        }
    }

    @Test
    void missingJwtConnectRejected() {
        ConnectedClient client = null;
        try {
            client = connectClient(null);
            client.session();
            fail("expected CONNECT without JWT to be rejected, errors: "
                    + (client != null ? client.handler().errors : "?"));
        } catch (Exception expected) {
            // rejected: future fails or session never establishes
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void invalidJwtConnectRejected() {
        ConnectedClient client = null;
        try {
            client = connectClient("not-a-jwt");
            client.session();
            fail("expected CONNECT with invalid JWT to be rejected, errors: "
                    + (client != null ? client.handler().errors : "?"));
        } catch (Exception expected) {
            // rejected
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void revokedSessionConnectRejected() throws Exception {
        User alice = createUser("rt_revoked_a");
        String token = loginAs(alice).accessToken();
        logout(token);

        ConnectedClient client = null;
        try {
            client = connectClient(token);
            client.session();
            fail("expected CONNECT with revoked session to be rejected, errors: "
                    + (client != null ? client.handler().errors : "?"));
        } catch (Exception expected) {
            // rejected
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void participantSubscribeSendAndBroadcast() throws Exception {
        User alice = createUser("rt_msg_a");
        User bob = createUser("rt_msg_b");
        befriend(alice, bob);
        LoginResponseDto aliceLogin = loginAs(alice);
        LoginResponseDto bobLogin = loginAs(bob);
        UUID conversationId = sendHttpMessage(aliceLogin.accessToken(), "rt_msg_b", "seed");

        ConnectedClient aliceClient = connectClient(aliceLogin.accessToken());
        ConnectedClient bobClient = connectClient(bobLogin.accessToken());
        try {
            aliceClient.session();
            bobClient.session();
            subscribe(aliceClient, "/topic/conversations/" + conversationId);
            subscribe(bobClient, "/topic/conversations/" + conversationId);

            UUID requestId = UUID.randomUUID();
            aliceClient.session().send("/app/chat.send",
                    chatSend(conversationId, "Hello realtime", requestId));

            MessageDto toAlice = aliceClient.handler().messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            MessageDto toBob = bobClient.handler().messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertNotNull(toAlice, "alice received broadcast");
            assertNotNull(toBob, "bob received broadcast");
            assertEquals(conversationId, toAlice.getConversationId());
            assertEquals(conversationId, toBob.getConversationId());
            assertEquals(toAlice.getMessageId(), toBob.getMessageId());
            assertEquals(alice.getUserId(), toAlice.getSenderUserId());
            assertEquals(2L, toAlice.getSequenceNumber());
            assertEquals("Hello realtime", toAlice.getContent());
            assertNotNull(toAlice.getServerTimestamp());
            assertEquals(requestId, toAlice.getRequestId());

            assertEquals(2, messageRepo.count());
            mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                            .header("Authorization", "Bearer " + aliceLogin.accessToken())
                            .param("afterSequence", "1"))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                            "$[0].messageId").value(toAlice.getMessageId().toString()));
        } finally {
            aliceClient.close();
            bobClient.close();
        }
    }

    @Test
    void duplicateRequestIdPreservesIdempotency() throws Exception {
        User alice = createUser("rt_dup_a");
        User bob = createUser("rt_dup_b");
        befriend(alice, bob);
        LoginResponseDto aliceLogin = loginAs(alice);
        LoginResponseDto bobLogin = loginAs(bob);
        UUID conversationId = sendHttpMessage(aliceLogin.accessToken(), "rt_dup_b", "seed");

        ConnectedClient aliceClient = connectClient(aliceLogin.accessToken());
        ConnectedClient bobClient = connectClient(bobLogin.accessToken());
        try {
            aliceClient.session();
            bobClient.session();
            subscribe(aliceClient, "/topic/conversations/" + conversationId);
            subscribe(bobClient, "/topic/conversations/" + conversationId);

            UUID requestId = UUID.randomUUID();
            aliceClient.session().send("/app/chat.send", chatSend(conversationId, "Once", requestId));
            MessageDto first = bobClient.handler().messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(first, "bob received first broadcast");

            aliceClient.session().send("/app/chat.send", chatSend(conversationId, "Once", requestId));
            MessageDto replay = bobClient.handler().messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(replay, "bob received replay broadcast");
            assertEquals(first.getMessageId(), replay.getMessageId());
            assertEquals(first.getSequenceNumber(), replay.getSequenceNumber());
            assertEquals(2, messageRepo.count());
        } finally {
            aliceClient.close();
            bobClient.close();
        }
    }

    @Test
    void nonParticipantSubscribeReceivesNothing() throws Exception {
        User alice = createUser("rt_sub_a");
        User bob = createUser("rt_sub_b");
        User mallory = createUser("rt_sub_m");
        befriend(alice, bob);
        LoginResponseDto aliceLogin = loginAs(alice);
        LoginResponseDto bobLogin = loginAs(bob);
        LoginResponseDto malloryLogin = loginAs(mallory);
        UUID conversationId = sendHttpMessage(aliceLogin.accessToken(), "rt_sub_b", "seed");

        ConnectedClient aliceClient = connectClient(aliceLogin.accessToken());
        ConnectedClient bobClient = connectClient(bobLogin.accessToken());
        ConnectedClient malloryClient = connectClient(malloryLogin.accessToken());
        try {
            aliceClient.session();
            bobClient.session();
            malloryClient.session();
            subscribe(aliceClient, "/topic/conversations/" + conversationId);
            subscribe(bobClient, "/topic/conversations/" + conversationId);
            subscribe(malloryClient, "/topic/conversations/" + conversationId);

            UUID requestId = UUID.randomUUID();
            aliceClient.session().send("/app/chat.send", chatSend(conversationId, "Private", requestId));

            assertNotNull(bobClient.handler().messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "participant receives broadcast");
            assertNull(malloryClient.handler().messages.poll(3, TimeUnit.SECONDS),
                    "non-participant receives nothing, errors: " + malloryClient.handler().errors);
        } finally {
            aliceClient.close();
            bobClient.close();
            malloryClient.close();
        }
    }

    @Test
    void unknownConversationSubscribeReceivesNothing() throws Exception {
        User mallory = createUser("rt_sub_unknown");
        LoginResponseDto malloryLogin = loginAs(mallory);

        ConnectedClient malloryClient = connectClient(malloryLogin.accessToken());
        try {
            malloryClient.session();
            subscribe(malloryClient, "/topic/conversations/" + UUID.randomUUID());

            assertNull(malloryClient.handler().messages.poll(3, TimeUnit.SECONDS),
                    "unknown conversation yields nothing, errors: " + malloryClient.handler().errors);
        } finally {
            malloryClient.close();
        }
    }

    @Test
    void failedSendDoesNotPersistOrBroadcast() throws Exception {
        User alice = createUser("rt_fail_a");
        User bob = createUser("rt_fail_b");
        User mallory = createUser("rt_fail_m");
        befriend(alice, bob);
        LoginResponseDto aliceLogin = loginAs(alice);
        LoginResponseDto bobLogin = loginAs(bob);
        LoginResponseDto malloryLogin = loginAs(mallory);
        UUID conversationId = sendHttpMessage(aliceLogin.accessToken(), "rt_fail_b", "seed");
        long countBefore = messageRepo.count();

        ConnectedClient aliceClient = connectClient(aliceLogin.accessToken());
        ConnectedClient bobClient = connectClient(bobLogin.accessToken());
        ConnectedClient malloryClient = connectClient(malloryLogin.accessToken());
        try {
            aliceClient.session();
            bobClient.session();
            malloryClient.session();
            subscribe(aliceClient, "/topic/conversations/" + conversationId);
            subscribe(bobClient, "/topic/conversations/" + conversationId);

            malloryClient.session().send("/app/chat.send",
                    chatSend(conversationId, "Intrude", UUID.randomUUID()));
            malloryClient.session().send("/app/chat.send",
                    chatSend(UUID.randomUUID(), "Ghost", UUID.randomUUID()));

            assertNull(aliceClient.handler().messages.poll(3, TimeUnit.SECONDS),
                    "no broadcast from failed sends, errors: " + aliceClient.handler().errors);
            assertNull(bobClient.handler().messages.poll(1, TimeUnit.SECONDS),
                    "no broadcast from failed sends, errors: " + bobClient.handler().errors);
            assertEquals(countBefore, messageRepo.count());
        } finally {
            aliceClient.close();
            bobClient.close();
            malloryClient.close();
        }
    }
}
