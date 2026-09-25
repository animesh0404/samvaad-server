package com.samvaad.samvaad_server.friendrequest;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.test.web.servlet.MvcResult;

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
class FriendsIntegrationTest {

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

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        user.setRole(UserRole.USER);
        return userRepo.save(user);
    }

    private String tokenFor(User user) {
        LoginResponseDto login = authenticationService.login(
                new LoginRequestDto(
                        user.getUsername(),
                        "secret123",
                        "inst-" + user.getUsername(),
                        ClientPlatform.WEB,
                        "Test Client",
                        "1.0.0"),
                "127.0.0.1",
                "UserAgent");
        return login.accessToken();
    }

    private void acceptFriendship(User sender, User recipient) {
        FriendRequestDto sent = friendRequestService.sendRequest(
                sender.getUserId(), recipient.getUsername());
        friendRequestService.acceptRequest(recipient.getUserId(), sent.getRequestId());
    }

    @Test
    void authenticatedUserReceivesTheirFriends() throws Exception {
        User alice = createUser("frnd_alice");
        User bob = createUser("frnd_bob");
        createUser("frnd_unrelated");
        acceptFriendship(alice, bob);

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(bob.getUserId().toString()))
                .andExpect(jsonPath("$[0].username").value("frnd_bob"));
    }

    @Test
    void friendshipVisibleWhenCurrentUserWasSender() throws Exception {
        User alice = createUser("frnd_snd_alice");
        User bob = createUser("frnd_snd_bob");
        acceptFriendship(alice, bob);

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(bob.getUserId().toString()));
    }

    @Test
    void friendshipVisibleWhenCurrentUserWasRecipient() throws Exception {
        User alice = createUser("frnd_rcp_alice");
        User bob = createUser("frnd_rcp_bob");
        acceptFriendship(alice, bob);

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(alice.getUserId().toString()))
                .andExpect(jsonPath("$[0].username").value("frnd_rcp_alice"));
    }

    @Test
    void onlyAcceptedRelationshipsAppear() throws Exception {
        User alice = createUser("frnd_mix_alice");
        User accepted = createUser("frnd_mix_accepted");
        User pending = createUser("frnd_mix_pending");
        User rejected = createUser("frnd_mix_rejected");
        User cancelled = createUser("frnd_mix_cancelled");

        acceptFriendship(alice, accepted);

        friendRequestService.sendRequest(alice.getUserId(), pending.getUsername());

        FriendRequestDto toReject = friendRequestService.sendRequest(
                alice.getUserId(), rejected.getUsername());
        friendRequestService.rejectRequest(rejected.getUserId(), toReject.getRequestId());

        FriendRequestDto toCancel = friendRequestService.sendRequest(
                alice.getUserId(), cancelled.getUsername());
        friendRequestService.cancelRequest(alice.getUserId(), toCancel.getRequestId());

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(accepted.getUserId().toString()));
    }

    @Test
    void pendingRelationshipsDoNotAppear() throws Exception {
        User alice = createUser("frnd_pen_alice");
        User bob = createUser("frnd_pen_bob");
        friendRequestService.sendRequest(alice.getUserId(), bob.getUsername());

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectedRelationshipsDoNotAppear() throws Exception {
        User alice = createUser("frnd_rej_alice");
        User bob = createUser("frnd_rej_bob");
        FriendRequestDto sent = friendRequestService.sendRequest(
                alice.getUserId(), bob.getUsername());
        friendRequestService.rejectRequest(bob.getUserId(), sent.getRequestId());

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cancelledRelationshipsDoNotAppear() throws Exception {
        User alice = createUser("frnd_can_alice");
        User bob = createUser("frnd_can_bob");
        FriendRequestDto sent = friendRequestService.sendRequest(
                alice.getUserId(), bob.getUsername());
        friendRequestService.cancelRequest(alice.getUserId(), sent.getRequestId());

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unrelatedUsersDoNotAppear() throws Exception {
        User alice = createUser("frnd_unr_alice");
        User bob = createUser("frnd_unr_bob");
        User carol = createUser("frnd_unr_carol");
        User dave = createUser("frnd_unr_dave");
        acceptFriendship(bob, carol);
        acceptFriendship(alice, dave);

        MvcResult result = mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("frnd_unr_dave"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(!body.contains("frnd_unr_bob"));
        assertTrue(!body.contains("frnd_unr_carol"));
    }

    @Test
    void currentUserNeverReturnedAsOwnFriend() throws Exception {
        User alice = createUser("frnd_self_alice");
        User bob = createUser("frnd_self_bob");
        acceptFriendship(alice, bob);

        FriendRequest self = new FriendRequest();
        self.setSender(alice);
        self.setRecipient(alice);
        self.setStatus(FriendRequestStatus.ACCEPTED);
        friendRequestRepo.saveAndFlush(self);

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(bob.getUserId().toString()));
    }

    @Test
    void noFriendsReturnsEmptyArray() throws Exception {
        User alice = createUser("frnd_lonely");
        createUser("frnd_lonely_other");

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void responseContainsOnlyUserIdAndUsername() throws Exception {
        User alice = createUser("frnd_shape_alice");
        User bob = createUser("frnd_shape_bob");
        acceptFriendship(alice, bob);

        MvcResult result = mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(bob.getUserId().toString()))
                .andExpect(jsonPath("$[0].username").value("frnd_shape_bob"))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].role").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"userId\""));
        assertTrue(body.contains("\"username\""));
        assertTrue(!body.contains("\"email\""));
        assertTrue(!body.contains("\"password\""));
        assertTrue(!body.contains("\"passwordHash\""));
        assertTrue(!body.contains("\"role\""));
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/friends"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void friendsAreOrderedByUsernameAscending() throws Exception {
        User alice = createUser("frnd_ord_alice");
        User zara = createUser("frnd_ord_zara");
        User mike = createUser("frnd_ord_mike");
        User anna = createUser("frnd_ord_anna");
        acceptFriendship(alice, zara);
        acceptFriendship(alice, mike);
        acceptFriendship(alice, anna);

        mockMvc.perform(get("/api/friends")
                        .header("Authorization", "Bearer " + tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].username").value("frnd_ord_anna"))
                .andExpect(jsonPath("$[1].username").value("frnd_ord_mike"))
                .andExpect(jsonPath("$[2].username").value("frnd_ord_zara"));
    }
}
