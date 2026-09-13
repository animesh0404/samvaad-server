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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class FriendRequestService {

    private static final Logger log = LoggerFactory.getLogger(FriendRequestService.class);

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
            log.warn("Friend request denied: self-request senderId={}", senderId);
            throw new ForbiddenOperationException();
        }

        if (!friendRequestRepo.findPendingBetween(senderId, recipient.getUserId()).isEmpty()) {
            log.warn("Friend request conflict: already pending senderId={} recipientId={}",
                    senderId, recipient.getUserId());
            throw new FriendRequestConflictException("Friend request already pending");
        }

        if (friendRequestRepo.existsAcceptedBetween(senderId, recipient.getUserId())) {
            log.warn("Friend request conflict: already friends senderId={} recipientId={}",
                    senderId, recipient.getUserId());
            throw new FriendRequestConflictException("Already friends");
        }

        FriendRequest request = new FriendRequest();
        request.setSender(sender);
        request.setRecipient(recipient);
        request.setStatus(FriendRequestStatus.PENDING);

        try {
            FriendRequest saved = friendRequestRepo.saveAndFlush(request);
            log.info("Friend request sent requestId={} senderId={} recipientId={}",
                    saved.getRequestId(), senderId, recipient.getUserId());
            return FriendRequestMapper.toDto(saved);
        } catch (DataIntegrityViolationException e) {
            log.warn("Friend request conflict: already pending senderId={} recipientId={}",
                    senderId, recipient.getUserId());
            throw new FriendRequestConflictException("Friend request already pending");
        }
    }

    @Transactional
    public FriendRequestDto acceptRequest(UUID callerId, UUID requestId) {
        FriendRequest request = loadPendingAsRecipient(callerId, requestId);

        request.setStatus(FriendRequestStatus.ACCEPTED);
        request.setRespondedAt(LocalDateTime.now());

        FriendRequestDto dto = FriendRequestMapper.toDto(friendRequestRepo.save(request));
        log.info("Friend request accepted requestId={} callerId={}", requestId, callerId);
        return dto;
    }

    @Transactional
    public FriendRequestDto rejectRequest(UUID callerId, UUID requestId) {
        FriendRequest request = loadPendingAsRecipient(callerId, requestId);

        request.setStatus(FriendRequestStatus.REJECTED);
        request.setRespondedAt(LocalDateTime.now());

        FriendRequestDto dto = FriendRequestMapper.toDto(friendRequestRepo.save(request));
        log.info("Friend request rejected requestId={} callerId={}", requestId, callerId);
        return dto;
    }

    @Transactional
    public FriendRequestDto cancelRequest(UUID callerId, UUID requestId) {
        FriendRequest request = friendRequestRepo.findByIdWithLock(requestId)
                .orElseThrow(() -> new FriendRequestNotFoundException(requestId));

        if (!request.getSender().getUserId().equals(callerId)) {
            log.warn("Friend request cancel denied requestId={} callerId={}", requestId, callerId);
            throw new ForbiddenOperationException();
        }

        if (request.getStatus() != FriendRequestStatus.PENDING) {
            log.warn("Friend request cancel conflict requestId={} callerId={} status={}",
                    requestId, callerId, request.getStatus());
            throw new FriendRequestConflictException("Friend request is no longer pending");
        }

        request.setStatus(FriendRequestStatus.CANCELLED);
        request.setRespondedAt(LocalDateTime.now());

        FriendRequestDto dto = FriendRequestMapper.toDto(friendRequestRepo.save(request));
        log.info("Friend request cancelled requestId={} callerId={}", requestId, callerId);
        return dto;
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
            log.warn("Friend request decision denied requestId={} callerId={}", requestId, callerId);
            throw new ForbiddenOperationException();
        }

        if (request.getStatus() != FriendRequestStatus.PENDING) {
            log.warn("Friend request decision conflict requestId={} callerId={} status={}",
                    requestId, callerId, request.getStatus());
            throw new FriendRequestConflictException("Friend request is no longer pending");
        }

        return request;
    }
}
