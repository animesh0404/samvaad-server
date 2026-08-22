package com.samvaad.samvaad_server.user;

import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepo userRepo;

    public UserService(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    public UserDto createUser(UserDto userDto) {

        if (userRepo.existsByUsername(userDto.getUsername())) {
            throw new UserAlreadyExistsException(userDto.getUsername());
        }

        User user = UserMapper.toEntity(userDto);
        User savedUser = userRepo.save(user);

        return UserMapper.toDto(savedUser);
    }
}