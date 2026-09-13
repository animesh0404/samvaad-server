package com.samvaad.samvaad_server.friendrequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.UserRepo;

@Service
public class FriendRequestService {

    private final FriendRequestRepo friendRequestRepo;
    private final UserRepo userRepo;

    public FriendRequestService(FriendRequestRepo friendRequestRepo, UserRepo userRepo) {
        this.friendRequestRepo = friendRequestRepo;
        this.userRepo = userRepo;
    }

    @Transactional
    public FriendRequestDto sendRequest(UUID senderId, String username) {
        User sender = userRepo.findById(senderId)
                .orElseThrow(() -> new UserNotFoundException(senderId));

        User recipient = userRepo.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new UserNotFoundException(username));

        if (recipient.getUserId().equals(senderId)) {
            throw new ForbiddenOperationException();
        }

        if (!friendRequestRepo.findPendingBetween(senderId, recipient.getUserId()).isEmpty()) {
            throw new FriendRequestConflictException("Friend request already pending");
        }

        if (friendRequestRepo.existsAcceptedBetween(senderId, recipient.getUserId())) {
            throw new FriendRequestConflictException("Already friends");
        }

        FriendRequest request = new FriendRequest();
        request.setSender(sender);
        request.setRecipient(recipient);
        request.setStatus(FriendRequestStatus.PENDING);

        try {
            FriendRequest saved = friendRequestRepo.saveAndFlush(request);
            return FriendRequestMapper.toDto(saved);
        } catch (DataIntegrityViolationException e) {
            throw new FriendRequestConflictException("Friend request already pending");
        }
    }

    @Transactional
    public FriendRequestDto acceptRequest(UUID callerId, UUID requestId) {
        FriendRequest request = loadPendingAsRecipient(callerId, requestId);

        request.setStatus(FriendRequestStatus.ACCEPTED);
        request.setRespondedAt(LocalDateTime.now());

        return FriendRequestMapper.toDto(friendRequestRepo.save(request));
    }

    @Transactional
    public FriendRequestDto rejectRequest(UUID callerId, UUID requestId) {
        FriendRequest request = loadPendingAsRecipient(callerId, requestId);

        request.setStatus(FriendRequestStatus.REJECTED);
        request.setRespondedAt(LocalDateTime.now());

        return FriendRequestMapper.toDto(friendRequestRepo.save(request));
    }

    @Transactional
    public FriendRequestDto cancelRequest(UUID callerId, UUID requestId) {
        FriendRequest request = friendRequestRepo.findByIdWithLock(requestId)
                .orElseThrow(() -> new FriendRequestNotFoundException(requestId));

        if (!request.getSender().getUserId().equals(callerId)) {
            throw new ForbiddenOperationException();
        }

        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new FriendRequestConflictException("Friend request is no longer pending");
        }

        request.setStatus(FriendRequestStatus.CANCELLED);
        request.setRespondedAt(LocalDateTime.now());

        return FriendRequestMapper.toDto(friendRequestRepo.save(request));
    }

    public List<FriendRequestDto> listIncoming(UUID callerId) {
        return friendRequestRepo
                .findByRecipientUserIdAndStatusOrderByCreatedAtDesc(callerId, FriendRequestStatus.PENDING)
                .stream()
                .map(FriendRequestMapper::toDto)
                .toList();
    }

    public List<FriendRequestDto> listOutgoing(UUID callerId) {
        return friendRequestRepo
                .findBySenderUserIdAndStatusOrderByCreatedAtDesc(callerId, FriendRequestStatus.PENDING)
                .stream()
                .map(FriendRequestMapper::toDto)
                .toList();
    }

    public boolean areFriends(UUID firstUserId, UUID secondUserId) {
        return friendRequestRepo.existsAcceptedBetween(firstUserId, secondUserId);
    }

    private FriendRequest loadPendingAsRecipient(UUID callerId, UUID requestId) {
        FriendRequest request = friendRequestRepo.findByIdWithLock(requestId)
                .orElseThrow(() -> new FriendRequestNotFoundException(requestId));

        if (!request.getRecipient().getUserId().equals(callerId)) {
            throw new ForbiddenOperationException();
        }

        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new FriendRequestConflictException("Friend request is no longer pending");
        }

        return request;
    }
}
