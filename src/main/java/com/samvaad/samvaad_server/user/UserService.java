package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepo userRepo;
    private final UserProfileService userProfileService;
    private final UserProfileRepo userProfileRepo;
    private final SessionRepo sessionRepo;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepo userRepo,
            UserProfileService userProfileService,
            UserProfileRepo userProfileRepo,
            SessionRepo sessionRepo,
            PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.userProfileService = userProfileService;
        this.userProfileRepo = userProfileRepo;
        this.sessionRepo = sessionRepo;
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

    public List<UserDto> listUsers() {
        return userRepo.findAll().stream()
                .map(UserMapper::toDto)
                .toList();
    }

    @Transactional
    public void deleteUser(UUID userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        sessionRepo.deleteByUserId(userId);
        userProfileRepo.deleteById(userId);
        userRepo.deleteById(userId);
    }
}
