package com.samvaad.samvaad_server.user.userprofile;

import com.samvaad.samvaad_server.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

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

        // Log changed field names only, never profile values.
        List<String> fields = new ArrayList<>();
        if (updateDto.hasFirstName()) {
            fields.add("firstName");
        }
        if (updateDto.hasMiddleName()) {
            fields.add("middleName");
        }
        if (updateDto.hasLastName()) {
            fields.add("lastName");
        }
        if (updateDto.hasDisplayName()) {
            fields.add("displayName");
        }
        if (updateDto.hasBio()) {
            fields.add("bio");
        }
        if (updateDto.hasAvatarUrl()) {
            fields.add("avatarUrl");
        }
        if (updateDto.hasStatusMessage()) {
            fields.add("statusMessage");
        }
        Collections.sort(fields);
        log.info("Profile updated userId={} fields={}", userId, fields);

        return UserProfileMapper.toDto(profile);
    }

    public void createProfile(User user) {
        UserProfile profile = new UserProfile();
        profile.setUser(user);

        userProfileRepo.save(profile);
    }
}
