package com.samvaad.samvaad_server.messaging;

public record SendMessageResult(MessageDto message, boolean created) {
}
