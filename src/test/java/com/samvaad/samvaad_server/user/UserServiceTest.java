package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepo, userProfileService, userProfileRepo, sessionRepo, passwordEncoder);
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
    void deleteUserRemovesSessionsProfileAndUser() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("target");

        given(userRepo.findById(userId)).willReturn(Optional.of(user));

        userService.deleteUser(userId);

        then(sessionRepo).should().deleteByUserId(userId);
        then(userProfileRepo).should().deleteById(userId);
        then(userRepo).should().deleteById(userId);
    }

    @Test
    void deleteNonexistentUserThrows() {
        UUID userId = UUID.randomUUID();

        given(userRepo.findById(userId)).willReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userService.deleteUser(userId));

        then(sessionRepo).should(never()).deleteByUserId(any());
        then(userProfileRepo).should(never()).deleteById(any());
        then(userRepo).should(never()).deleteById(any());
    }

    @Test
    void deleteUserWithNoDependencies() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("target");

        given(userRepo.findById(userId)).willReturn(Optional.of(user));

        userService.deleteUser(userId);

        then(sessionRepo).should().deleteByUserId(userId);
        then(userProfileRepo).should().deleteById(userId);
        then(userRepo).should().deleteById(userId);
    }
}
