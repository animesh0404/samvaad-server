package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.config.BootstrapAdminProperties;
import com.samvaad.samvaad_server.user.userprofile.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

    @Mock
    private UserRepo userRepo;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminBootstrapService adminBootstrapService;

    @BeforeEach
    void setUp() {
        adminBootstrapService = new AdminBootstrapService(userRepo, userProfileService, passwordEncoder);
    }

    @Test
    void createsAdminWithBcryptHashWhenNoneExists() {
        BootstrapAdminProperties properties =
                new BootstrapAdminProperties("root", "root-secret", "root@example.com");

        given(userRepo.existsByRole(UserRole.ADMIN)).willReturn(false);
        given(passwordEncoder.encode("root-secret")).willReturn("$2a$10$admin-hash");
        given(userRepo.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

        adminBootstrapService.bootstrap(properties);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        then(userRepo).should().save(userCaptor.capture());
        User admin = userCaptor.getValue();

        assertEquals("root", admin.getUsername());
        assertEquals("root@example.com", admin.getEmail());
        assertEquals(UserRole.ADMIN, admin.getRole());
        assertEquals("$2a$10$admin-hash", admin.getPasswordHash());
        then(userProfileService).should().createProfile(admin);
    }

    @Test
    void isIdempotentWhenAnAdminAlreadyExists() {
        BootstrapAdminProperties properties =
                new BootstrapAdminProperties("root", "root-secret", "root@example.com");

        given(userRepo.existsByRole(UserRole.ADMIN)).willReturn(true);

        adminBootstrapService.bootstrap(properties);

        then(userRepo).should(never()).save(any(User.class));
        then(passwordEncoder).shouldHaveNoInteractions();
        then(userProfileService).shouldHaveNoInteractions();
    }

    @Test
    void skipsCreationWhenCredentialsAreMissing() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties("", "", null);

        adminBootstrapService.bootstrap(properties);

        then(userRepo).shouldHaveNoInteractions();
        then(passwordEncoder).shouldHaveNoInteractions();
        then(userProfileService).shouldHaveNoInteractions();
    }
}