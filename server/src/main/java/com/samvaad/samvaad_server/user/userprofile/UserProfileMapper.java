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

    public static void updateEntity(UserProfile profile, UserProfileUpdateDto updateDto) {
        if (updateDto.hasDisplayName()) {
            profile.setDisplayName(updateDto.getDisplayName());
        }
        if (updateDto.hasBio()) {
            profile.setBio(updateDto.getBio());
        }
        if (updateDto.hasAvatarUrl()) {
            profile.setAvatarUrl(updateDto.getAvatarUrl());
        }
        if (updateDto.hasFirstName()) {
            profile.setFirstName(updateDto.getFirstName());
        }
        if (updateDto.hasMiddleName()) {
            profile.setMiddleName(updateDto.getMiddleName());
        }
        if (updateDto.hasLastName()) {
            profile.setLastName(updateDto.getLastName());
        }
        if (updateDto.hasStatusMessage()) {
            profile.setStatusMessage(updateDto.getStatusMessage());
        }
    }
}
