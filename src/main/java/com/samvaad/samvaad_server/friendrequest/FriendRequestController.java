package com.samvaad.samvaad_server.friendrequest;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.security.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/friend-requests")
public class FriendRequestController {

    private final FriendRequestService friendRequestService;

    public FriendRequestController(FriendRequestService friendRequestService) {
        this.friendRequestService = friendRequestService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FriendRequestDto sendRequest(@Valid @RequestBody SendFriendRequestDto request) {
        AuthenticatedUser caller = CurrentUser.require();
        return friendRequestService.sendRequest(caller.userId(), request.getUsername());
    }

    @GetMapping("/incoming")
    public List<FriendRequestDto> listIncoming() {
        AuthenticatedUser caller = CurrentUser.require();
        return friendRequestService.listIncoming(caller.userId());
    }

    @GetMapping("/outgoing")
    public List<FriendRequestDto> listOutgoing() {
        AuthenticatedUser caller = CurrentUser.require();
        return friendRequestService.listOutgoing(caller.userId());
    }

    @PostMapping("/{requestId}/accept")
    public FriendRequestDto acceptRequest(@PathVariable UUID requestId) {
        AuthenticatedUser caller = CurrentUser.require();
        return friendRequestService.acceptRequest(caller.userId(), requestId);
    }

    @PostMapping("/{requestId}/reject")
    public FriendRequestDto rejectRequest(@PathVariable UUID requestId) {
        AuthenticatedUser caller = CurrentUser.require();
        return friendRequestService.rejectRequest(caller.userId(), requestId);
    }

    @PostMapping("/{requestId}/cancel")
    public FriendRequestDto cancelRequest(@PathVariable UUID requestId) {
        AuthenticatedUser caller = CurrentUser.require();
        return friendRequestService.cancelRequest(caller.userId(), requestId);
    }
}
