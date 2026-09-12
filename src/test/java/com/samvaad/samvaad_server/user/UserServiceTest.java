package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.user.userprofile.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepo userRepo;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepo, userProfileService, passwordEncoder);
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
}