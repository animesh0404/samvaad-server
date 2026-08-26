package com.samvaad.samvaad_server.user.userprofile;

public final class UserProfileMapper {

    private UserProfileMapper() {
    }

    public static UserProfileDto toDto(UserProfile profile) {
        UserProfileDto dto = new UserProfileDto();

        dto.setUserId(profile.getUserId());
        dto.setDisplayName(profile.getDisplayName());
        dto.setBio(profile.getBio());
        dto.setAvatarUrl(profile.getAvatarUrl());
        dto.setFirstName(profile.getFirstName());
        dto.setMiddleName(profile.getMiddleName());
        dto.setLastName(profile.getLastName());
        dto.setStatusMessage(profile.getStatusMessage());

        return dto;
    }
}