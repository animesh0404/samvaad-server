package com.samvaad.samvaad_server.friendrequest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.messaging.ConversationRepo;
import com.samvaad.samvaad_server.messaging.MessageRepo;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FriendRequestIntegrationTest {

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
    private FriendRequestService friendRequestService;

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

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(post("/api/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/friend-requests/incoming"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/friend-requests/outgoing"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullLifecycleSendAcceptBecomesFriends() throws Exception {
        User alice = createUser("fr_alice", UserRole.USER);
        User bob = createUser("fr_bob", UserRole.USER);
        String aliceToken = loginAs(alice).accessToken();
        String bobToken = loginAs(bob).accessToken();

        String requestId = mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_bob"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.senderUsername").value("fr_alice"))
                .andExpect(jsonPath("$.recipientUsername").value("fr_bob"))
                .andReturn().getResponse().getContentAsString()
                .split("\"requestId\":\"")[1].split("\"")[0];

        mockMvc.perform(get("/api/friend-requests/outgoing")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/friend-requests/incoming")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value(requestId));

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertTrue(friendRequestService.areFriends(alice.getUserId(), bob.getUserId()));
        assertTrue(friendRequestService.areFriends(bob.getUserId(), alice.getUserId()));

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_bob"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotSendRequestToSelf() throws Exception {
        User alice = createUser("fr_self", UserRole.USER);
        String token = loginAs(alice).accessToken();

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_self"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotSendToUnknownUser() throws Exception {
        User alice = createUser("fr_known", UserRole.USER);
        String token = loginAs(alice).accessToken();

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ghost"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicatePendingSendConflicts() throws Exception {
        User alice = createUser("fr_dup_a", UserRole.USER);
        User bob = createUser("fr_dup_b", UserRole.USER);
        String aliceToken = loginAs(alice).accessToken();

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_dup_b"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_dup_b"}
                                """))
                .andExpect(status().isConflict());

        assertTrue(!friendRequestService.areFriends(alice.getUserId(), bob.getUserId()));
    }

    @Test
    void reversePendingSendConflicts() throws Exception {
        User alice = createUser("fr_rev_a", UserRole.USER);
        User bob = createUser("fr_rev_b", UserRole.USER);
        String aliceToken = loginAs(alice).accessToken();
        String bobToken = loginAs(bob).accessToken();

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_rev_b"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_rev_a"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectThenResendAllowed() throws Exception {
        User alice = createUser("fr_rej_a", UserRole.USER);
        User bob = createUser("fr_rej_b", UserRole.USER);
        String aliceToken = loginAs(alice).accessToken();
        String bobToken = loginAs(bob).accessToken();

        String requestId = mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_rej_b"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .split("\"requestId\":\"")[1].split("\"")[0];

        mockMvc.perform(post("/api/friend-requests/{requestId}/reject", requestId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertTrue(!friendRequestService.areFriends(alice.getUserId(), bob.getUserId()));

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_rej_b"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void cancelThenResendAllowed() throws Exception {
        User alice = createUser("fr_can_a", UserRole.USER);
        User bob = createUser("fr_can_b", UserRole.USER);
        String aliceToken = loginAs(alice).accessToken();

        String requestId = mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_can_b"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .split("\"requestId\":\"")[1].split("\"")[0];

        mockMvc.perform(post("/api/friend-requests/{requestId}/cancel", requestId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/friend-requests/outgoing")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_can_b"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void wrongPartyMutationsAreForbidden() throws Exception {
        User alice = createUser("fr_wp_a", UserRole.USER);
        User bob = createUser("fr_wp_b", UserRole.USER);
        User mallory = createUser("fr_wp_m", UserRole.USER);
        String aliceToken = loginAs(alice).accessToken();
        String malloryToken = loginAs(mallory).accessToken();

        String requestId = mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_wp_b"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .split("\"requestId\":\"")[1].split("\"")[0];

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + malloryToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/friend-requests/{requestId}/reject", requestId)
                        .header("Authorization", "Bearer " + malloryToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/friend-requests/{requestId}/cancel", requestId)
                        .header("Authorization", "Bearer " + malloryToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/friend-requests/{requestId}/reject", requestId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/friend-requests/{requestId}/cancel", requestId)
                        .header("Authorization", "Bearer " + loginAs(bob).accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void terminalTransitionsConflict() throws Exception {
        User alice = createUser("fr_term_a", UserRole.USER);
        User bob = createUser("fr_term_b", UserRole.USER);
        String aliceToken = loginAs(alice).accessToken();
        String bobToken = loginAs(bob).accessToken();

        String requestId = mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fr_term_b"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .split("\"requestId\":\"")[1].split("\"")[0];

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/friend-requests/{requestId}/accept", requestId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/friend-requests/{requestId}/cancel", requestId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
    }

    @Test
    void blankUsernameReturns400() throws Exception {
        User alice = createUser("fr_blank", UserRole.USER);
        String token = loginAs(alice).accessToken();

        mockMvc.perform(post("/api/friend-requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void databaseRejectsDuplicatePendingPair() {
        User alice = createUser("fr_db_a", UserRole.USER);
        User bob = createUser("fr_db_b", UserRole.USER);

        FriendRequest first = new FriendRequest();
        first.setSender(alice);
        first.setRecipient(bob);
        first.setStatus(FriendRequestStatus.PENDING);
        friendRequestRepo.saveAndFlush(first);

        FriendRequest reverse = new FriendRequest();
        reverse.setSender(bob);
        reverse.setRecipient(alice);
        reverse.setStatus(FriendRequestStatus.PENDING);

        assertThrows(DataIntegrityViolationException.class,
                () -> friendRequestRepo.saveAndFlush(reverse));
    }
}
