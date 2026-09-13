package com.samvaad.samvaad_server.friendrequest;

public final class FriendRequestMapper {

    private FriendRequestMapper() {
    }

    public static FriendRequestDto toDto(FriendRequest request) {
        FriendRequestDto dto = new FriendRequestDto();
        dto.setRequestId(request.getRequestId());
        dto.setSenderUserId(request.getSender().getUserId());
        dto.setSenderUsername(request.getSender().getUsername());
        dto.setRecipientUserId(request.getRecipient().getUserId());
        dto.setRecipientUsername(request.getRecipient().getUsername());
        dto.setStatus(request.getStatus());
        dto.setCreatedAt(request.getCreatedAt());
        dto.setRespondedAt(request.getRespondedAt());
        return dto;
    }
}
