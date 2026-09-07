package com.samvaad.samvaad_server.user.userprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UserProfileMapperTest {

    @Test
    void updatesOnlyFieldsProvidedByPatchRequest() {
        UserProfile profile = new UserProfile();
        profile.setDisplayName("Existing name");
        profile.setBio("Existing bio");
        profile.setStatusMessage("Away");

        UserProfileUpdateDto updateDto = new UserProfileUpdateDto();
        updateDto.setDisplayName("Updated name");
        updateDto.setStatusMessage("Available");

        UserProfileMapper.updateEntity(profile, updateDto);

        assertEquals("Updated name", profile.getDisplayName());
        assertEquals("Existing bio", profile.getBio());
        assertEquals("Available", profile.getStatusMessage());
    }
}
