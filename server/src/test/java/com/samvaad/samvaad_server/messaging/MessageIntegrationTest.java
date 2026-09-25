package com.samvaad.samvaad_server.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.friendrequest.FriendRequestRepo;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MessageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo oneTimePrekeyRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo recoveryCodeRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo deviceRepo;

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
    private FriendRequestService friendRequestService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        messageRepo.deleteAll();
        conversationRepo.deleteAll();
        friendRequestRepo.deleteAll();
        oneTimePrekeyRepo.deleteAll();
        recoveryCodeRepo.deleteAll();
        sessionRepo.deleteAll();
        deviceRepo.deleteAll();
        userProfileRepo.deleteAll();
        userRepo.deleteAll();
    }

    private User createUser(String username, UserRole role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        user.setRole(role);
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
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        try {
            mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId)
                            .header("Authorization", "Bearer " + otherToken))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sendMessage(String token, String username, String content, UUID requestId) throws Exception {
        return mockMvc.perform(post("/api/conversations/direct/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","content":"%s","requestId":"%s"}
                                """.formatted(username, content, requestId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value(content))
                .andExpect(jsonPath("$.serverTimestamp").exists())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void unauthenticatedSendReturns401() throws Exception {
        mockMvc.perform(post("/api/conversations/direct/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","content":"Hello","requestId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void friendCanSendMessage() throws Exception {
        User alice = createUser("msg_alice", UserRole.USER);
        User bob = createUser("msg_bob", UserRole.USER);
        befriend(alice, bob);
        String token = loginAs(alice).accessToken();

        String body = sendMessage(token, "msg_bob", "Hello Bob", UUID.randomUUID());

        assertTrue(body.contains("\"sequenceNumber\":1"));
        assertTrue(body.contains("\"senderUserId\":\"" + alice.getUserId() + "\""));
    }

    @Test
    void nonFriendCannotSend() throws Exception {
        User alice = createUser("msg_stranger_a", UserRole.USER);
        User mallory = createUser("msg_stranger_b", UserRole.USER);
        String token = loginAs(alice).accessToken();

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"msg_stranger_b","content":"Hello","requestId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        assertTrue(messageRepo.findAll().isEmpty());
        assertTrue(conversationRepo.findAll().isEmpty());
    }

    @Test
    void cannotMessageSelf() throws Exception {
        User alice = createUser("msg_self", UserRole.USER);
        String token = loginAs(alice).accessToken();

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"msg_self","content":"Hello","requestId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRecipientReturns404() throws Exception {
        User alice = createUser("msg_known", UserRole.USER);
        String token = loginAs(alice).accessToken();

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ghost","content":"Hello","requestId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankContentReturns400() throws Exception {
        User alice = createUser("msg_blank", UserRole.USER);
        String token = loginAs(alice).accessToken();

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"msg_blank","content":"","requestId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void samePairSharesOneConversationWithOrderedSequences() throws Exception {
        User alice = createUser("msg_seq_a", UserRole.USER);
        User bob = createUser("msg_seq_b", UserRole.USER);
        befriend(alice, bob);
        String aliceToken = loginAs(alice).accessToken();
        String bobToken = loginAs(bob).accessToken();

        String first = sendMessage(aliceToken, "msg_seq_b", "One", UUID.randomUUID());
        String second = sendMessage(bobToken, "msg_seq_a", "Two", UUID.randomUUID());
        String third = sendMessage(aliceToken, "msg_seq_b", "Three", UUID.randomUUID());

        String conversationId = first.split("\"conversationId\":\"")[1].split("\"")[0];

        assertTrue(first.contains("\"sequenceNumber\":1"));
        assertTrue(second.contains("\"sequenceNumber\":2"));
        assertTrue(third.contains("\"sequenceNumber\":3"));
        assertTrue(second.contains("\"conversationId\":\"" + conversationId + "\""));
        assertTrue(third.contains("\"conversationId\":\"" + conversationId + "\""));
        assertTrue(conversationRepo.findAll().size() == 1);
    }

    @Test
    void replayedRequestIdReturnsOriginalWithoutDuplicate() throws Exception {
        User alice = createUser("msg_replay_a", UserRole.USER);
        User bob = createUser("msg_replay_b", UserRole.USER);
        befriend(alice, bob);
        String token = loginAs(alice).accessToken();
        UUID requestId = UUID.randomUUID();

        String first = sendMessage(token, "msg_replay_b", "Hello", requestId);
        String messageId = first.split("\"messageId\":\"")[1].split("\"")[0];

        mockMvc.perform(post("/api/conversations/direct/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"msg_replay_b","content":"Hello","requestId":"%s"}
                                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(messageId));

        assertTrue(messageRepo.findAll().size() == 1);
    }

    @Test
    void databaseEnforcesConversationAndSequenceUniqueness() {
        User alice = createUser("msg_db_a", UserRole.USER);
        User bob = createUser("msg_db_b", UserRole.USER);

        Conversation conversation = Conversation.between(alice.getUserId(), bob.getUserId());
        conversationRepo.saveAndFlush(conversation);

        Conversation duplicate = Conversation.between(bob.getUserId(), alice.getUserId());
        assertThrows(DataIntegrityViolationException.class,
                () -> conversationRepo.saveAndFlush(duplicate));
    }
}
