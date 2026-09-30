package com.samvaad.samvaad_server.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
    private PasswordEncoder passwordEncoder;

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
        // Ciphertext transport tables precede conversations/users: RESTRICT
        // foreign keys forbid deleting a conversation or sender still
        // referenced by durable ciphertext rows.
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

    private Conversation createConversation(User first, User second) {
        return conversationRepo.save(Conversation.between(first.getUserId(), second.getUserId()));
    }

    @Test
    void unauthenticatedListReturns401() throws Exception {
        mockMvc.perform(get("/api/conversations/direct"))
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
        String token = loginAs(alice).accessToken();

        Conversation conversation = createConversation(alice, bob);

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].conversationId").value(conversation.getConversationId().toString()))
                .andExpect(jsonPath("$[0].otherParticipantUserId").value(bob.getUserId().toString()))
                .andExpect(jsonPath("$[0].otherParticipantUsername").value("read_bob"));
    }

    @Test
    void listOrdersByRecentActivityFirst() throws Exception {
        User alice = createUser("read_order_a");
        User bob = createUser("read_order_b");
        User carol = createUser("read_order_c");
        String token = loginAs(alice).accessToken();

        createConversation(alice, carol);
        Conversation second = createConversation(alice, bob);
        // Mark the second conversation as most recently active.
        second.setLastSequenceNumber(second.getLastSequenceNumber() + 1);
        conversationRepo.saveAndFlush(second);

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].conversationId").value(second.getConversationId().toString()))
                .andExpect(jsonPath("$[0].otherParticipantUsername").value("read_order_b"))
                .andExpect(jsonPath("$[1].otherParticipantUsername").value("read_order_c"));
    }

    @Test
    void listIsParticipantScoped() throws Exception {
        User alice = createUser("read_scope_a");
        User bob = createUser("read_scope_b");
        User mallory = createUser("read_scope_m");
        String aliceToken = loginAs(alice).accessToken();
        String malloryToken = loginAs(mallory).accessToken();

        createConversation(alice, bob);

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/conversations/direct")
                        .header("Authorization", "Bearer " + malloryToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidPaginationReturns400() throws Exception {
        User alice = createUser("read_badpage");
        String token = loginAs(alice).accessToken();

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
    }
}
