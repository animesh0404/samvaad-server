package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException;
import com.samvaad.samvaad_server.common.logging.LogCapture;
import com.samvaad.samvaad_server.friendrequest.FriendRequestRepo;
import com.samvaad.samvaad_server.messaging.Conversation;
import com.samvaad.samvaad_server.messaging.ConversationRepo;
import com.samvaad.samvaad_server.messaging.MessageRepo;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepo userRepo;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private UserProfileRepo userProfileRepo;

    @Mock
    private SessionRepo sessionRepo;

    @Mock
    private FriendRequestRepo friendRequestRepo;

    @Mock
    private MessageRepo messageRepo;

    @Mock
    private ConversationRepo conversationRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepo, userProfileService, userProfileRepo, sessionRepo,
                friendRequestRepo, messageRepo, conversationRepo, passwordEncoder);
    }

    @Test
    void hashesPasswordAndForcesUserRoleOnProvisioning() {
        CreateUserRequestDto request =
                new CreateUserRequestDto("animesh", "plain-secret", "animesh@example.com");

        given(userRepo.existsByUsername("animesh")).willReturn(false);
        given(passwordEncoder.encode("plain-secret")).willReturn("$2a$10$hashed-secret");
        given(userRepo.save(any(User.class))).willAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setUserId(UUID.randomUUID());
            return user;
        });

        UserDto dto = userService.createUser(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        then(userRepo).should().save(userCaptor.capture());
        User saved = userCaptor.getValue();

        assertEquals("$2a$10$hashed-secret", saved.getPasswordHash());
        assertNotEquals("plain-secret", saved.getPasswordHash());
        assertEquals(UserRole.USER, saved.getRole());
        then(userProfileService).should().createProfile(saved);

        assertNotNull(dto.getUserId());
        assertEquals("animesh", dto.getUsername());
        assertEquals(UserRole.USER, dto.getRole());
    }

    @Test
    void rejectsDuplicateUsernameWithoutPersistingAnything() {
        CreateUserRequestDto request =
                new CreateUserRequestDto("animesh", "plain-secret", null);

        given(userRepo.existsByUsername("animesh")).willReturn(true);

        assertThrows(UserAlreadyExistsException.class, () -> userService.createUser(request));

        then(passwordEncoder).shouldHaveNoInteractions();
        then(userRepo).should(org.mockito.Mockito.never()).save(any(User.class));
        then(userProfileService).shouldHaveNoInteractions();
    }

    @Test
    void listUsersReturnsAllUsers() {
        User user1 = new User(UUID.randomUUID());
        user1.setUsername("admin1");
        user1.setEmail("admin1@example.com");
        user1.setRole(UserRole.ADMIN);

        User user2 = new User(UUID.randomUUID());
        user2.setUsername("user1");
        user2.setEmail("user1@example.com");
        user2.setRole(UserRole.USER);

        given(userRepo.findAll()).willReturn(List.of(user1, user2));

        List<UserDto> result = userService.listUsers();

        assertEquals(2, result.size());
        assertEquals("admin1", result.get(0).getUsername());
        assertEquals("user1", result.get(1).getUsername());
        then(userRepo).should().findAll();
    }

    @Test
    void listUsersReturnsEmptyWhenNoUsers() {
        given(userRepo.findAll()).willReturn(List.of());

        List<UserDto> result = userService.listUsers();

        assertTrue(result.isEmpty());
        then(userRepo).should().findAll();
    }

    @Test
    void deleteUserRemovesSessionsProfileAndUserInForeignKeySafeOrder() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("target");

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(conversationRepo.findByParticipantAOrParticipantB(eq(userId), eq(userId), any(Pageable.class)))
                .willReturn(List.of());

        userService.deleteUser(userId);

        InOrder order = inOrder(sessionRepo, userProfileRepo, friendRequestRepo, messageRepo,
                conversationRepo, userRepo);
        order.verify(sessionRepo).deleteByUserId(userId);
        order.verify(userProfileRepo).deleteById(userId);
        order.verify(friendRequestRepo).deleteByParticipantUserId(userId);
        order.verify(messageRepo).deleteBySenderUserId(userId);
        order.verify(conversationRepo).findByParticipantAOrParticipantB(eq(userId), eq(userId), any(Pageable.class));
        order.verify(userRepo).deleteById(userId);
    }

    @Test
    void deleteNonexistentUserThrows() {
        UUID userId = UUID.randomUUID();

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userService.deleteUser(userId));

        then(sessionRepo).should(never()).deleteByUserId(any());
        then(userProfileRepo).should(never()).deleteById(any());
        then(friendRequestRepo).should(never()).deleteByParticipantUserId(any());
        then(messageRepo).should(never()).deleteBySenderUserId(any());
        then(conversationRepo).should(never()).findByParticipantAOrParticipantB(any(), any(), any());
        then(userRepo).should(never()).deleteById(any());
    }

    @Test
    void deleteUserRemovesConversationMessagesBeforeTheirConversations() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("target");
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = new Conversation();
        conversation.setConversationId(conversationId);

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(conversationRepo.findByParticipantAOrParticipantB(eq(userId), eq(userId), any(Pageable.class)))
                .willReturn(List.of(conversation));

        userService.deleteUser(userId);

        InOrder order = inOrder(messageRepo, conversationRepo);
        order.verify(messageRepo).deleteByConversationConversationId(conversationId);
        order.verify(conversationRepo).deleteById(conversationId);
    }

    @Test
    void deleteUserFlushConflictThrowsDeletionConflictWithoutSuccessLog() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("target");

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(conversationRepo.findByParticipantAOrParticipantB(eq(userId), eq(userId), any(Pageable.class)))
                .willReturn(List.of());
        willThrow(new DataIntegrityViolationException("fk race")).given(userRepo).flush();

        try (LogCapture logs = new LogCapture(UserService.class)) {
            assertThrows(UserDeletionConflictException.class, () -> userService.deleteUser(userId));
            assertTrue(logs.text().contains("User deletion conflict"),
                    "expected conflict warning, got:\n" + logs.text());
            assertTrue(!logs.text().contains("User deleted userId="),
                    "success must not be logged on failure, got:\n" + logs.text());
        }
    }

    @Test
    void deleteUserLogsSuccessWithoutTransaction() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("target");

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(conversationRepo.findByParticipantAOrParticipantB(eq(userId), eq(userId), any(Pageable.class)))
                .willReturn(List.of());

        try (LogCapture logs = new LogCapture(UserService.class)) {
            userService.deleteUser(userId);
            assertTrue(logs.text().contains("User deleted userId=" + userId),
                    "expected success log, got:\n" + logs.text());
        }
    }

    @Test
    void changesEmailSuccessfully() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("user1");
        user.setEmail("old@example.com");
        user.setPasswordHash("$2a$10$hashed");
        user.setRole(UserRole.USER);

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(userRepo.findByEmailIgnoreCase("new@example.com")).willReturn(List.of());
        given(userRepo.saveAndFlush(user)).willReturn(user);

        UserDto result = userService.changeEmail(userId, "new@example.com");

        assertEquals("new@example.com", result.getEmail());
        assertEquals("user1", result.getUsername());
        assertEquals(UserRole.USER, result.getRole());
        assertEquals("new@example.com", user.getEmail());
        assertEquals("user1", user.getUsername());
        assertEquals("$2a$10$hashed", user.getPasswordHash());
        assertEquals(UserRole.USER, user.getRole());
        then(userRepo).should().saveAndFlush(user);
    }

    @Test
    void changeEmailNonexistentUserThrows() {
        UUID userId = UUID.randomUUID();

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userService.changeEmail(userId, "new@example.com"));

        then(userRepo).should(never()).findByEmailIgnoreCase(any());
        then(userRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void changeEmailDuplicateBelongingToAnotherUserThrows() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("user1");
        user.setEmail("old@example.com");

        User other = new User(UUID.randomUUID());
        other.setUsername("user2");
        other.setEmail("Taken@Example.com");

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(userRepo.findByEmailIgnoreCase("taken@example.com")).willReturn(List.of(other));

        assertThrows(EmailAlreadyExistsException.class,
                () -> userService.changeEmail(userId, "taken@example.com"));

        assertEquals("old@example.com", user.getEmail());
        then(userRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void changeEmailSameEmailIsNoOp() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("user1");
        user.setEmail("same@example.com");

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));

        UserDto result = userService.changeEmail(userId, "same@example.com");

        assertEquals("same@example.com", result.getEmail());
        then(userRepo).should(never()).findByEmailIgnoreCase(any());
        then(userRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void changeEmailCaseOnlyVariantIsNoOp() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("user1");
        user.setEmail("User@Example.com");

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));

        UserDto result = userService.changeEmail(userId, "user@example.com");

        assertEquals("User@Example.com", result.getEmail());
        then(userRepo).should(never()).findByEmailIgnoreCase(any());
        then(userRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void changeEmailConstraintViolationMapsToConflict() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("user1");
        user.setEmail("old@example.com");

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(userRepo.findByEmailIgnoreCase("race@example.com")).willReturn(List.of());
        given(userRepo.saveAndFlush(user)).willThrow(new DataIntegrityViolationException("duplicate"));

        assertThrows(EmailAlreadyExistsException.class,
                () -> userService.changeEmail(userId, "race@example.com"));
    }

    @Test
    void changesPasswordSuccessfully() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("user1");
        user.setEmail("user1@example.com");
        user.setPasswordHash("$2a$10$oldhash");
        user.setRole(UserRole.USER);

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("old-secret", "$2a$10$oldhash")).willReturn(true);
        given(passwordEncoder.encode("new-secret")).willReturn("$2a$10$newhash");
        given(userRepo.save(user)).willReturn(user);

        UserDto result = userService.changePassword(userId, "old-secret", "new-secret");

        assertEquals("$2a$10$newhash", user.getPasswordHash());
        assertNotEquals("new-secret", user.getPasswordHash());
        assertEquals("user1", result.getUsername());
        assertEquals("user1@example.com", result.getEmail());
        assertEquals(UserRole.USER, result.getRole());
        assertEquals(userId, result.getUserId());
        then(userRepo).should().save(user);
    }

    @Test
    void changePasswordNonexistentUserThrows() {
        UUID userId = UUID.randomUUID();

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> userService.changePassword(userId, "old-secret", "new-secret"));

        then(passwordEncoder).shouldHaveNoInteractions();
        then(userRepo).should(never()).save(any());
    }

    @Test
    void changePasswordWrongCurrentPasswordThrowsWithoutWriting() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("user1");
        user.setPasswordHash("$2a$10$oldhash");

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong-secret", "$2a$10$oldhash")).willReturn(false);

        assertThrows(IncorrectPasswordException.class,
                () -> userService.changePassword(userId, "wrong-secret", "new-secret"));

        assertEquals("$2a$10$oldhash", user.getPasswordHash());
        then(passwordEncoder).should(never()).encode(any());
        then(userRepo).should(never()).save(any());
    }

    @Test
    void changePasswordEqualToCurrentIsAllowed() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("user1");
        user.setPasswordHash("$2a$10$oldhash");

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("same-secret", "$2a$10$oldhash")).willReturn(true);
        given(passwordEncoder.encode("same-secret")).willReturn("$2a$10$samehash");
        given(userRepo.save(user)).willReturn(user);

        UserDto result = userService.changePassword(userId, "same-secret", "same-secret");

        assertEquals("$2a$10$samehash", user.getPasswordHash());
        assertEquals(userId, result.getUserId());
        then(userRepo).should().save(user);
    }

    @Test
    void lookupByExactUsername() {
        User user = new User(UUID.randomUUID());
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setRole(UserRole.USER);

        given(userRepo.findByUsernameIgnoreCase("alice")).willReturn(Optional.of(user));

        UserLookupDto result = userService.lookupByUsername("alice");

        assertEquals(user.getUserId(), result.getUserId());
        assertEquals("alice", result.getUsername());
    }

    @Test
    void lookupMatchesCaseInsensitively() {
        User user = new User(UUID.randomUUID());
        user.setUsername("alice");

        given(userRepo.findByUsernameIgnoreCase("ALICE")).willReturn(Optional.of(user));

        UserLookupDto result = userService.lookupByUsername("ALICE");

        assertEquals("alice", result.getUsername());
        then(userRepo).should().findByUsernameIgnoreCase("ALICE");
    }

    @Test
    void lookupNonexistentUsernameThrows() {
        given(userRepo.findByUsernameIgnoreCase("ghost")).willReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userService.lookupByUsername("ghost"));
    }
}
