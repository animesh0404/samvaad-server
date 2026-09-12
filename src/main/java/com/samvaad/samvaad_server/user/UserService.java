package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.user.userprofile.UserProfileService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepo userRepo;
    private final UserProfileService userProfileService;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepo userRepo,
            UserProfileService userProfileService,
            PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.userProfileService = userProfileService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserDto createUser(CreateUserRequestDto request) {

        if (userRepo.existsByUsername(request.getUsername())) {
            throw new UserAlreadyExistsException(request.getUsername());
        }

        User user = UserMapper.toEntity(request);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);

        User savedUser = userRepo.save(user);

        userProfileService.createProfile(savedUser);

        return UserMapper.toDto(savedUser);
    }

    public UserDto getUser(UUID userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        return UserMapper.toDto(user);
    }
}