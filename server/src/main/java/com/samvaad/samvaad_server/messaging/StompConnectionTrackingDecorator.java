package com.samvaad.samvaad_server.messaging;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Tracks live WebSocket transport sessions for the connection registry.
 *
 * <p>Registered through the standard transport decorator factories, so it
 * wraps the STOMP broker handler like the framework's own decorators.
 * STOMP authentication happens after the transport exists, so sessions are
 * recorded here (unauthenticated) and linked to the server session once
 * CONNECT succeeds. Every transport closure unregisters, bounding the
 * registry to concurrently open sockets.
 */
public class StompConnectionTrackingDecorator extends WebSocketHandlerDecorator {

    private final StompConnectionRegistry connectionRegistry;

    public StompConnectionTrackingDecorator(
            WebSocketHandler delegate, StompConnectionRegistry connectionRegistry) {
        super(delegate);
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        connectionRegistry.trackTransportSession(session);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
            throws Exception {
        try {
            super.afterConnectionClosed(session, closeStatus);
        } finally {
            connectionRegistry.unlinkTransportSession(session.getId());
        }
    }
}
