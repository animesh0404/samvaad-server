package com.samvaad.samvaad_server.user;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException;
import com.samvaad.samvaad_server.common.logging.LogCapture;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileService;

import ch.qos.logback.classic.Level;

@ExtendWith(MockitoExtension.class)
class UserServiceLoggingTest {

    private static final String CURRENT_PASSWORD = "current-password-secret-xyz";
    private static final String NEW_PASSWORD = "brand-new-password-secret-xyz";

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
        userService = new UserService(
                userRepo, userProfileService, userProfileRepo, sessionRepo, passwordEncoder);
    }

    private User userWithPassword(UUID userId) {
        User user = new User(userId);
        user.setUsername("animesh");
        user.setPasswordHash("hashed-password");
        return user;
    }

    @Test
    void logsPasswordChangeWithoutPasswords() {
        UUID userId = UUID.randomUUID();
        User user = userWithPassword(userId);

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(CURRENT_PASSWORD, "hashed-password")).willReturn(true);
        given(passwordEncoder.encode(NEW_PASSWORD)).willReturn("new-hash");
        given(userRepo.save(user)).willReturn(user);

        try (LogCapture logs = new LogCapture(UserService.class)) {
            userService.changePassword(userId, CURRENT_PASSWORD, NEW_PASSWORD);

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains(userId.toString())));
            String text = logs.text();
            assertTrue(!text.contains(CURRENT_PASSWORD), "password must never be logged");
            assertTrue(!text.contains(NEW_PASSWORD), "password must never be logged");
        }
    }

    @Test
    void logsPasswordMismatchAtWarnWithoutPasswords() {
        UUID userId = UUID.randomUUID();
        User user = userWithPassword(userId);

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(CURRENT_PASSWORD, "hashed-password")).willReturn(false);

        try (LogCapture logs = new LogCapture(UserService.class)) {
            assertThrows(IncorrectPasswordException.class,
                    () -> userService.changePassword(userId, CURRENT_PASSWORD, NEW_PASSWORD));

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains(userId.toString())));
            String text = logs.text();
            assertTrue(!text.contains(CURRENT_PASSWORD), "password must never be logged");
            assertTrue(!text.contains(NEW_PASSWORD), "password must never be logged");
        }
    }

    @Test
    void logsEmailConflictWithoutEmailValue() {
        UUID userId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        User user = userWithPassword(userId);
        User other = new User(otherId);
        String takenEmail = "taken-email-secret-xyz@example.com";

        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(userRepo.findByEmailIgnoreCase(takenEmail)).willReturn(java.util.List.of(other));

        try (LogCapture logs = new LogCapture(UserService.class)) {
            assertThrows(EmailAlreadyExistsException.class,
                    () -> userService.changeEmail(userId, takenEmail));

            assertTrue(logs.events().stream().anyMatch(e -> e.getLevel() == Level.WARN));
            assertTrue(!logs.text().contains(takenEmail), "email value must not be logged");
        }
    }
}
