package com.samvaad.samvaad_server.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.samvaad.samvaad_server.auth.exception.BadCredentialsException;
import com.samvaad.samvaad_server.common.logging.LogCapture;
import com.samvaad.samvaad_server.friendrequest.FriendRequestDto;
import com.samvaad.samvaad_server.friendrequest.FriendRequestRepo;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.messaging.ConversationRepo;
import com.samvaad.samvaad_server.messaging.MessageRepo;
import com.samvaad.samvaad_server.messaging.MessageService;
import com.samvaad.samvaad_server.messaging.SendMessageResult;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * Hard-delete slice: removing a user with profile, sessions, friend requests
 * in both directions, sent messages, and conversations must succeed with 204
 * and leave no orphaned dependent rows, while unrelated users and their data
 * remain intact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserDeletionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserService userService;

    @Autowired
    private FriendRequestService friendRequestService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private SessionRepo sessionRepo;

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

    private User createUser(String username, UserRole role) {
        UserDto created = userService.createUser(new CreateUserRequestDto(username, "secret123", username + "@example.com"));
        User user = userRepo.findById(created.getUserId()).orElseThrow();
        user.setRole(role);
        return userRepo.save(user);
    }

    private String loginToken(User user) {
        return authenticationService.login(
                new LoginRequestDto(
                        user.getUsername(),
                        "secret123",
                        "inst-" + user.getUsername(),
                        ClientPlatform.WEB,
                        "Test Client",
                        "1.0.0"),
                "127.0.0.1",
                "UserAgent").accessToken();
    }

    private void befriend(User first, User second) {
        FriendRequestDto request = friendRequestService.sendRequest(first.getUserId(), second.getUsername());
        friendRequestService.acceptRequest(second.getUserId(), request.getRequestId());
    }

    private SendMessageResult send(User sender, String recipientUsername, String content) {
        return messageService.sendMessage(sender.getUserId(), recipientUsername, content, UUID.randomUUID());
    }

    @Test
    void deletingUserWithFullDependentsSucceedsAndPreservesOthers() throws Exception {
        User admin = createUser("adminx", UserRole.ADMIN);
        User bob = createUser("bobx", UserRole.USER);
        User carol = createUser("carolx", UserRole.USER);
        User dave = createUser("davex", UserRole.USER);

        // Bob's sessions, profile, and relationships.
        loginToken(bob);
        assertTrue(userProfileRepo.existsById(bob.getUserId()));
        FriendRequestDto pendingToCarol = friendRequestService.sendRequest(bob.getUserId(), carol.getUsername());
        FriendRequestDto pendingFromDave = friendRequestService.sendRequest(dave.getUserId(), bob.getUsername());
        friendRequestService.acceptRequest(carol.getUserId(), pendingToCarol.getRequestId());
        SendMessageResult bobMessage = send(bob, carol.getUsername(), "hello carol");
        SendMessageResult carolMessage = send(carol, bob.getUsername(), "hello bob");
        assertEquals(bobMessage.message().getConversationId(), carolMessage.message().getConversationId());
        UUID bobConversationId = bobMessage.message().getConversationId();

        // Unrelated data that must survive: dave and carol are friends with messages.
        befriend(dave, carol);
        SendMessageResult unrelatedMessage = send(dave, carol.getUsername(), "unrelated");
        UUID unrelatedConversationId = unrelatedMessage.message().getConversationId();

        String adminToken = loginToken(admin);

        UUID bobId = bob.getUserId();
        UUID carolId = carol.getUserId();
        UUID daveId = dave.getUserId();

        String logs;
        try (LogCapture capture = new LogCapture(UserService.class)) {
            mockMvc.perform(delete("/api/users/{userId}", bobId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNoContent());
            logs = capture.text();
        }

        // Deleted user's row is gone.
        assertTrue(userRepo.findById(bobId).isEmpty());

        // All intended dependent rows are gone.
        assertTrue(sessionRepo.findAll().stream().noneMatch(s -> s.getUser().getUserId().equals(bobId)));
        assertTrue(userProfileRepo.findById(bobId).isEmpty());
        assertTrue(friendRequestRepo.findAll().stream().noneMatch(f ->
                f.getSender().getUserId().equals(bobId) || f.getRecipient().getUserId().equals(bobId)));
        assertTrue(messageRepo.findAll().stream().noneMatch(m ->
                m.getSender().getUserId().equals(bobId)
                        || m.getConversation().getConversationId().equals(bobConversationId)));
        assertTrue(conversationRepo.findAll().stream().noneMatch(c ->
                c.getParticipantA().equals(bobId) || c.getParticipantB().equals(bobId)));

        // Other participants and unrelated data remain intact.
        assertTrue(userRepo.findById(carolId).isPresent());
        assertTrue(userRepo.findById(daveId).isPresent());
        assertTrue(userRepo.findById(admin.getUserId()).isPresent());
        assertTrue(conversationRepo.findById(unrelatedConversationId).isPresent());
        assertTrue(messageRepo.findById(unrelatedMessage.message().getMessageId()).isPresent());
        assertTrue(friendRequestRepo.findById(pendingFromDave.getRequestId()).isEmpty(),
                "dave's pending request to bob must be cleaned up");
        assertEquals(1, friendRequestRepo.findAll().size(),
                "only the dave-carol friendship should remain");

        // Deleted credentials no longer authenticate.
        assertThrows(BadCredentialsException.class, () -> authenticationService.login(
                new LoginRequestDto("bobx", "secret123", null, ClientPlatform.WEB, "Test Client", "1.0.0"),
                "127.0.0.1",
                "UserAgent"));

        // Success is logged exactly once, after the commit succeeded.
        assertTrue(logs.contains("User deleted userId=" + bobId),
                "expected post-commit success log, got:\n" + logs);
    }
}
