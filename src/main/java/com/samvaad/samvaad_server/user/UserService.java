package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.user.userprofile.UserProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepo userRepo;
    private final UserProfileService userProfileService;

    public UserService(
            UserRepo userRepo,
            UserProfileService userProfileService) {
        this.userRepo = userRepo;
        this.userProfileService = userProfileService;
    }

    @Transactional
    public UserDto createUser(UserDto userDto) {

        if (userRepo.existsByUsername(userDto.getUsername())) {
            throw new UserAlreadyExistsException(userDto.getUsername());
        }

        User user = UserMapper.toEntity(userDto);
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