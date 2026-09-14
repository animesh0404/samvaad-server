package com.samvaad.samvaad_server.user;

import java.util.List;

public final class UserMapper {

    private UserMapper() {
    }

    public static User toEntity(UserDto dto) {
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        return user;
    }

    public static User toEntity(CreateUserRequestDto dto) {
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        return user;
    }

    public static UserDto toDto(User user) {
        UserDto dto = new UserDto();
        dto.setUserId(user.getUserId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());
        return dto;
    }

    public static UserLookupDto toLookupDto(User user) {
        UserLookupDto dto = new UserLookupDto();
        dto.setUserId(user.getUserId());
        dto.setUsername(user.getUsername());
        return dto;
    }

    public static List<User> toEntity(List<UserDto> userDtos) {
        return userDtos.stream()
                .map(UserMapper::toEntity)
                .toList();
    }

    public static List<UserDto> toDto(List<User> users) {
        return users.stream()
                .map(UserMapper::toDto)
                .toList();
    }
}