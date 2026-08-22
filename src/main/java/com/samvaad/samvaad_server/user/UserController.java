package com.samvaad.samvaad_server.user;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto createUser(@Valid @RequestBody UserDto userDto) {
        return userService.createUser(userDto);
    }

    @GetMapping("/{userId}")
    public UserDto getUser(@PathVariable UUID userId) {
        return userService.getUser(userId);
    }
}