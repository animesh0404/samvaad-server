package com.samvaad.samvaad_server.user.userprofile;

import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.security.CurrentUser;
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
        AuthenticatedUser caller = CurrentUser.require();
        if (!ProfileAccessPolicy.canRead(caller, userId)) {
            throw new ForbiddenOperationException();
        }
        return userProfileService.getProfile(userId);
    }

    @PatchMapping
    public UserProfileDto updateProfile(
            @PathVariable UUID userId,
            @Valid @RequestBody UserProfileUpdateDto updateDto) {
        AuthenticatedUser caller = CurrentUser.require();
        if (!ProfileAccessPolicy.canWrite(caller, userId)) {
            throw new ForbiddenOperationException();
        }
        return userProfileService.updateProfile(userId, updateDto);
    }
}