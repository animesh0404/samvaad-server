package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.security.CurrentUser;
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
    public UserDto createUser(@Valid @RequestBody CreateUserRequestDto request) {
        return userService.createUser(request);
    }

    @GetMapping("/{userId}")
    public UserDto getUser(@PathVariable UUID userId) {
        AuthenticatedUser caller = CurrentUser.require();
        if (!caller.userId().equals(userId) && caller.role() != UserRole.ADMIN) {
            throw new ForbiddenOperationException();
        }
        return userService.getUser(userId);
    }
}