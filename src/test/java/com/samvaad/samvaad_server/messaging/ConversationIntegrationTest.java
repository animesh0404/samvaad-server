package com.samvaad.samvaad_server.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConversationIntegrationTest {

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

    private String sendMessage(String token, String username, String content) {
        try {
            return mockMvc.perform(post("/api/conversations/direct/messages")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"%s","content":"%s","requestId":"%s"}
                                    """.formatted(username, content, UUID.randomUUID())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.content").value(content))
                    .andReturn().getResponse().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String conversationIdFrom(String messageBody) {
        return messageBody.split("\"conversationId\":\"")[1].split("\"")[0];
    }

    @Test
    void unauthenticatedListReturns401() throws Exception {
        mockMvc.perform(get("/api/conversations/direct"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedMessagesReturns401() throws Exception {
        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyListForNewUser() throws Exception {
        User alice = createUser("read_newbie");
        String token = loginAs(alice).accessToken();

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listShowsConversationAfterSend() throws Exception {
        User alice = createUser("read_alice");
        User bob = createUser("read_bob");
        befriend(alice, bob);
        String token = loginAs(alice).accessToken();

        String body = sendMessage(token, "read_bob", "Hello Bob");
        String conversationId = conversationIdFrom(body);

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].conversationId").value(conversationId))
                .andExpect(jsonPath("$[0].otherParticipantUserId").value(bob.getUserId().toString()))
                .andExpect(jsonPath("$[0].otherParticipantUsername").value("read_bob"))
                .andExpect(jsonPath("$[0].lastSequenceNumber").value(1));
    }

    @Test
    void listOrdersByRecentActivityFirst() throws Exception {
        User alice = createUser("read_order_a");
        User bob = createUser("read_order_b");
        User carol = createUser("read_order_c");
        befriend(alice, bob);
        befriend(alice, carol);
        String token = loginAs(alice).accessToken();

        sendMessage(token, "read_order_c", "First conversation");
        String secondBody = sendMessage(token, "read_order_b", "Second conversation");
        String secondConversationId = conversationIdFrom(secondBody);

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].conversationId").value(secondConversationId))
                .andExpect(jsonPath("$[0].otherParticipantUsername").value("read_order_b"))
                .andExpect(jsonPath("$[1].otherParticipantUsername").value("read_order_c"));
    }

    @Test
    void listIsParticipantScoped() throws Exception {
        User alice = createUser("read_scope_a");
        User bob = createUser("read_scope_b");
        User mallory = createUser("read_scope_m");
        befriend(alice, bob);
        String aliceToken = loginAs(alice).accessToken();
        String malloryToken = loginAs(mallory).accessToken();

        sendMessage(aliceToken, "read_scope_b", "Private");

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + malloryToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nonParticipantCannotReadMessages() throws Exception {
        User alice = createUser("read_priv_a");
        User bob = createUser("read_priv_b");
        User mallory = createUser("read_priv_m");
        befriend(alice, bob);
        String aliceToken = loginAs(alice).accessToken();
        String malloryToken = loginAs(mallory).accessToken();

        String conversationId = conversationIdFrom(sendMessage(aliceToken, "read_priv_b", "Private"));

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + malloryToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownConversationReturns404() throws Exception {
        User alice = createUser("read_ghost");
        String token = loginAs(alice).accessToken();

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void messagesAreOrderedAndPaginatedBySequence() throws Exception {
        User alice = createUser("read_page_a");
        User bob = createUser("read_page_b");
        befriend(alice, bob);
        String aliceToken = loginAs(alice).accessToken();
        String bobToken = loginAs(bob).accessToken();

        String firstBody = sendMessage(aliceToken, "read_page_b", "One");
        String conversationId = conversationIdFrom(firstBody);
        sendMessage(bobToken, "read_page_a", "Two");
        sendMessage(aliceToken, "read_page_b", "Three");
        sendMessage(bobToken, "read_page_a", "Four");
        sendMessage(aliceToken, "read_page_b", "Five");

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$[0].content").value("One"))
                .andExpect(jsonPath("$[1].sequenceNumber").value(2));

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("afterSequence", "2")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sequenceNumber").value(3))
                .andExpect(jsonPath("$[1].sequenceNumber").value(4));

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + bobToken)
                        .param("afterSequence", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sequenceNumber").value(5));

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("afterSequence", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidPaginationReturns400() throws Exception {
        User alice = createUser("read_badpage");
        User bob = createUser("read_badpage_b");
        befriend(alice, bob);
        String token = loginAs(alice).accessToken();
        String conversationId = conversationIdFrom(sendMessage(token, "read_badpage_b", "Hello"));

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + token)
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + token)
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + token)
                        .param("offset", "-1"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/conversations/direct/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .param("afterSequence", "-1"))
                .andExpect(status().isBadRequest());
    }
}
