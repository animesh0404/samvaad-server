package com.samvaad.samvaad_server.user.userprofile;

import com.samvaad.samvaad_server.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserProfileService {

    private final UserProfileRepo userProfileRepo;

    public UserProfileService(UserProfileRepo userProfileRepo) {
        this.userProfileRepo = userProfileRepo;
    }

    public UserProfileDto getProfile(UUID userId) {
        UserProfile profile = userProfileRepo.findById(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));

        return UserProfileMapper.toDto(profile);
    }

    @Transactional
    public UserProfileDto updateProfile(UUID userId, UserProfileUpdateDto updateDto) {
        UserProfile profile = userProfileRepo.findById(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));

        UserProfileMapper.updateEntity(profile, updateDto);

        return UserProfileMapper.toDto(profile);
    }

    public void createProfile(User user) {
        UserProfile profile = new UserProfile();
        profile.setUser(user);

        userProfileRepo.save(profile);
    }
}
