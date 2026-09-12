package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.config.BootstrapAdminProperties;
import com.samvaad.samvaad_server.user.userprofile.UserProfileService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminBootstrapService {

    private final UserRepo userRepo;
    private final UserProfileService userProfileService;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapService(
            UserRepo userRepo,
            UserProfileService userProfileService,
            PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.userProfileService = userProfileService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void bootstrap(BootstrapAdminProperties properties) {
        if (!properties.hasCredentials()) {
            return;
        }

        if (userRepo.existsByRole(UserRole.ADMIN)) {
            return;
        }

        User admin = new User();
        admin.setUsername(properties.username());
        admin.setEmail(properties.email());
        admin.setPasswordHash(passwordEncoder.encode(properties.password()));
        admin.setRole(UserRole.ADMIN);

        User savedAdmin = userRepo.save(admin);
        userProfileService.createProfile(savedAdmin);
    }
}