package com.samvaad.samvaad_server.friendrequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserRole;

@ExtendWith(MockitoExtension.class)
class FriendRequestServiceTest {

    @Mock
    private FriendRequestRepo friendRequestRepo;

    @Mock
    private UserRepo userRepo;

    private FriendRequestService friendRequestService;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        friendRequestService = new FriendRequestService(friendRequestRepo, userRepo);

        alice = new User(UUID.randomUUID());
        alice.setUsername("alice");
        alice.setRole(UserRole.USER);

        bob = new User(UUID.randomUUID());
        bob.setUsername("bob");
        bob.setRole(UserRole.USER);
    }

    private FriendRequest pendingRequest() {
        FriendRequest request = new FriendRequest();
        request.setRequestId(UUID.randomUUID());
        request.setSender(alice);
        request.setRecipient(bob);
        request.setStatus(FriendRequestStatus.PENDING);
        return request;
    }

    @Test
    void sendsPendingRequest() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(friendRequestRepo.findPendingBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(List.of());
        given(friendRequestRepo.existsAcceptedBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(false);
        given(friendRequestRepo.saveAndFlush(any(FriendRequest.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        FriendRequestDto result = friendRequestService.sendRequest(alice.getUserId(), "bob");

        assertEquals(FriendRequestStatus.PENDING, result.getStatus());
        assertEquals(alice.getUserId(), result.getSenderUserId());
        assertEquals(bob.getUserId(), result.getRecipientUserId());
    }

    @Test
    void cannotSendRequestToSelf() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("alice")).willReturn(Optional.of(alice));

        assertThrows(ForbiddenOperationException.class,
                () -> friendRequestService.sendRequest(alice.getUserId(), "alice"));

        then(friendRequestRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void cannotSendRequestToUnknownUser() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("ghost")).willReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> friendRequestService.sendRequest(alice.getUserId(), "ghost"));

        then(friendRequestRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void duplicatePendingRequestConflicts() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(friendRequestRepo.findPendingBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(List.of(pendingRequest()));

        assertThrows(FriendRequestConflictException.class,
                () -> friendRequestService.sendRequest(alice.getUserId(), "bob"));

        then(friendRequestRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void reversePendingRequestConflicts() {
        FriendRequest reverse = new FriendRequest();
        reverse.setSender(bob);
        reverse.setRecipient(alice);
        reverse.setStatus(FriendRequestStatus.PENDING);

        given(userRepo.findById(bob.getUserId())).willReturn(Optional.of(bob));
        given(userRepo.findByUsernameIgnoreCase("alice")).willReturn(Optional.of(alice));
        given(friendRequestRepo.findPendingBetween(bob.getUserId(), alice.getUserId()))
                .willReturn(List.of(reverse));

        assertThrows(FriendRequestConflictException.class,
                () -> friendRequestService.sendRequest(bob.getUserId(), "alice"));

        then(friendRequestRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void cannotSendRequestWhenAlreadyFriends() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(friendRequestRepo.findPendingBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(List.of());
        given(friendRequestRepo.existsAcceptedBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(true);

        assertThrows(FriendRequestConflictException.class,
                () -> friendRequestService.sendRequest(alice.getUserId(), "bob"));

        then(friendRequestRepo).should(never()).saveAndFlush(any());
    }

    @Test
    void terminalHistoryAllowsNewRequest() {
        FriendRequest rejected = pendingRequest();
        rejected.setStatus(FriendRequestStatus.REJECTED);

        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(friendRequestRepo.findPendingBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(List.of());
        given(friendRequestRepo.existsAcceptedBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(false);
        given(friendRequestRepo.saveAndFlush(any(FriendRequest.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        FriendRequestDto result = friendRequestService.sendRequest(alice.getUserId(), "bob");

        assertEquals(FriendRequestStatus.PENDING, result.getStatus());
    }

    @Test
    void raceOnDuplicatePendingMapsToConflict() {
        given(userRepo.findById(alice.getUserId())).willReturn(Optional.of(alice));
        given(userRepo.findByUsernameIgnoreCase("bob")).willReturn(Optional.of(bob));
        given(friendRequestRepo.findPendingBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(List.of());
        given(friendRequestRepo.existsAcceptedBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(false);
        given(friendRequestRepo.saveAndFlush(any(FriendRequest.class)))
                .willThrow(new DataIntegrityViolationException("duplicate"));

        assertThrows(FriendRequestConflictException.class,
                () -> friendRequestService.sendRequest(alice.getUserId(), "bob"));
    }

    @Test
    void recipientAcceptsPendingRequest() {
        FriendRequest request = pendingRequest();

        given(friendRequestRepo.findByIdWithLock(request.getRequestId()))
                .willReturn(Optional.of(request));
        given(friendRequestRepo.save(request)).willReturn(request);

        FriendRequestDto result =
                friendRequestService.acceptRequest(bob.getUserId(), request.getRequestId());

        assertEquals(FriendRequestStatus.ACCEPTED, result.getStatus());
        assertEquals(FriendRequestStatus.ACCEPTED, request.getStatus());
        assertNotNull(request.getRespondedAt());
    }

    @Test
    void senderCannotAcceptOwnRequest() {
        FriendRequest request = pendingRequest();

        given(friendRequestRepo.findByIdWithLock(request.getRequestId()))
                .willReturn(Optional.of(request));

        assertThrows(ForbiddenOperationException.class,
                () -> friendRequestService.acceptRequest(alice.getUserId(), request.getRequestId()));

        assertEquals(FriendRequestStatus.PENDING, request.getStatus());
        then(friendRequestRepo).should(never()).save(any());
    }

    @Test
    void cannotAcceptNonPendingRequest() {
        FriendRequest request = pendingRequest();
        request.setStatus(FriendRequestStatus.CANCELLED);

        given(friendRequestRepo.findByIdWithLock(request.getRequestId()))
                .willReturn(Optional.of(request));

        assertThrows(FriendRequestConflictException.class,
                () -> friendRequestService.acceptRequest(bob.getUserId(), request.getRequestId()));

        then(friendRequestRepo).should(never()).save(any());
    }

    @Test
    void acceptNonexistentRequestThrows() {
        UUID requestId = UUID.randomUUID();

        given(friendRequestRepo.findByIdWithLock(requestId)).willReturn(Optional.empty());

        assertThrows(FriendRequestNotFoundException.class,
                () -> friendRequestService.acceptRequest(bob.getUserId(), requestId));
    }

    @Test
    void recipientRejectsPendingRequest() {
        FriendRequest request = pendingRequest();

        given(friendRequestRepo.findByIdWithLock(request.getRequestId()))
                .willReturn(Optional.of(request));
        given(friendRequestRepo.save(request)).willReturn(request);

        FriendRequestDto result =
                friendRequestService.rejectRequest(bob.getUserId(), request.getRequestId());

        assertEquals(FriendRequestStatus.REJECTED, result.getStatus());
        assertNotNull(request.getRespondedAt());
    }

    @Test
    void senderCannotRejectOwnRequest() {
        FriendRequest request = pendingRequest();

        given(friendRequestRepo.findByIdWithLock(request.getRequestId()))
                .willReturn(Optional.of(request));

        assertThrows(ForbiddenOperationException.class,
                () -> friendRequestService.rejectRequest(alice.getUserId(), request.getRequestId()));

        then(friendRequestRepo).should(never()).save(any());
    }

    @Test
    void senderCancelsPendingRequest() {
        FriendRequest request = pendingRequest();

        given(friendRequestRepo.findByIdWithLock(request.getRequestId()))
                .willReturn(Optional.of(request));
        given(friendRequestRepo.save(request)).willReturn(request);

        FriendRequestDto result =
                friendRequestService.cancelRequest(alice.getUserId(), request.getRequestId());

        assertEquals(FriendRequestStatus.CANCELLED, result.getStatus());
        assertNotNull(request.getRespondedAt());
    }

    @Test
    void recipientCannotCancelRequest() {
        FriendRequest request = pendingRequest();

        given(friendRequestRepo.findByIdWithLock(request.getRequestId()))
                .willReturn(Optional.of(request));

        assertThrows(ForbiddenOperationException.class,
                () -> friendRequestService.cancelRequest(bob.getUserId(), request.getRequestId()));

        then(friendRequestRepo).should(never()).save(any());
    }

    @Test
    void cannotCancelNonPendingRequest() {
        FriendRequest request = pendingRequest();
        request.setStatus(FriendRequestStatus.ACCEPTED);

        given(friendRequestRepo.findByIdWithLock(request.getRequestId()))
                .willReturn(Optional.of(request));

        assertThrows(FriendRequestConflictException.class,
                () -> friendRequestService.cancelRequest(alice.getUserId(), request.getRequestId()));

        then(friendRequestRepo).should(never()).save(any());
    }

    @Test
    void listsIncomingAndOutgoing() {
        FriendRequest incoming = new FriendRequest();
        incoming.setSender(bob);
        incoming.setRecipient(alice);
        incoming.setStatus(FriendRequestStatus.PENDING);

        given(friendRequestRepo.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(
                alice.getUserId(), FriendRequestStatus.PENDING)).willReturn(List.of(incoming));
        given(friendRequestRepo.findBySenderUserIdAndStatusOrderByCreatedAtDesc(
                alice.getUserId(), FriendRequestStatus.PENDING)).willReturn(List.of());

        assertEquals(1, friendRequestService.listIncoming(alice.getUserId()).size());
        assertEquals(0, friendRequestService.listOutgoing(alice.getUserId()).size());
    }

    @Test
    void areFriendsReflectsAcceptedRow() {
        given(friendRequestRepo.existsAcceptedBetween(alice.getUserId(), bob.getUserId()))
                .willReturn(true);

        assertTrue(friendRequestService.areFriends(alice.getUserId(), bob.getUserId()));
        assertFalse(friendRequestService.areFriends(alice.getUserId(), UUID.randomUUID()));
    }
}
