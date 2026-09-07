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
        if (updateDto.getDisplayName() != null) {
            profile.setDisplayName(updateDto.getDisplayName());
        }
        if (updateDto.getBio() != null) {
            profile.setBio(updateDto.getBio());
        }
        if (updateDto.getAvatarUrl() != null) {
            profile.setAvatarUrl(updateDto.getAvatarUrl());
        }
        if (updateDto.getFirstName() != null) {
            profile.setFirstName(updateDto.getFirstName());
        }
        if (updateDto.getMiddleName() != null) {
            profile.setMiddleName(updateDto.getMiddleName());
        }
        if (updateDto.getLastName() != null) {
            profile.setLastName(updateDto.getLastName());
        }
        if (updateDto.getStatusMessage() != null) {
            profile.setStatusMessage(updateDto.getStatusMessage());
        }
    }
}
