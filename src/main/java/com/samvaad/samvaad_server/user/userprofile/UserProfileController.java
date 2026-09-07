package com.samvaad.samvaad_server.user.userprofile;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users/{userId}/profile")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    public UserProfileDto getProfile(@PathVariable UUID userId) {
        return userProfileService.getProfile(userId);
    }

    @PatchMapping
    public UserProfileDto updateProfile(
            @PathVariable UUID userId,
            @Valid @RequestBody UserProfileUpdateDto updateDto) {
        return userProfileService.updateProfile(userId, updateDto);
    }
}
