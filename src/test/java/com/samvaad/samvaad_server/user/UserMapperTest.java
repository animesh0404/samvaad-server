package com.samvaad.samvaad_server.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UserMapperTest {

    @Test
    void mapsEmailInBothDirections() {
        UUID userId = UUID.randomUUID();
        UserDto createDto = new UserDto();
        createDto.setUsername("animesh");
        createDto.setEmail("animesh@example.com");

        User user = UserMapper.toEntity(createDto);
        user.setUserId(userId);

        UserDto result = UserMapper.toDto(user);

        assertEquals("animesh", user.getUsername());
        assertEquals("animesh@example.com", user.getEmail());
        assertEquals(userId, result.getUserId());
        assertEquals("animesh", result.getUsername());
        assertEquals("animesh@example.com", result.getEmail());
    }

    @Test
    void mapsLookupDtoWithoutPrivateFields() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPasswordHash("$2a$10$hashed");
        user.setRole(UserRole.ADMIN);

        UserLookupDto result = UserMapper.toLookupDto(user);

        assertEquals(userId, result.getUserId());
        assertEquals("alice", result.getUsername());
    }
}
