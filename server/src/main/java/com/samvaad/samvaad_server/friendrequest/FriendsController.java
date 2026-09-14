package com.samvaad.samvaad_server.friendrequest;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.security.CurrentUser;
import com.samvaad.samvaad_server.user.UserLookupDto;

@RestController
@RequestMapping("/api/friends")
public class FriendsController {

    private final FriendRequestService friendRequestService;

    public FriendsController(FriendRequestService friendRequestService) {
        this.friendRequestService = friendRequestService;
    }

    @GetMapping
    public List<UserLookupDto> listFriends() {
        AuthenticatedUser caller = CurrentUser.require();
        return friendRequestService.listFriends(caller.userId());
    }
}
