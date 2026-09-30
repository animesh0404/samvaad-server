package com.samvaad.samvaad_server.messaging;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * In-memory registry of live authenticated STOMP/WebSocket connections.
 *
 * <p>Single-server V1 scope: no distribution, no persistence. A transport
 * session is tracked when the WebSocket handshake completes; it is linked
 * to the server-authenticated session once STOMP CONNECT succeeds. When a
 * server session is revoked, every live connection bound to it is closed
 * server-side so a revoked session cannot keep receiving realtime events.
 * Every transport closure unregisters, so repeated termination is a safe
 * no-op.
 */
@Component
public class StompConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(StompConnectionRegistry.class);

    private final ConcurrentHashMap<String, WebSocketSession> transportSessions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> simpSessionsByServerSession =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> serverSessionBySimpSession =
            new ConcurrentHashMap<>();

    /**
     * Records a freshly established transport session. Called before STOMP
     * authentication; the session becomes terminable only once
     * {@link #linkAuthenticatedSession} binds it to a server session.
     */
    public void trackTransportSession(WebSocketSession session) {
        transportSessions.put(session.getId(), session);
    }

    /**
     * Binds an authenticated STOMP connection to its server session.
     * Identity comes from the already-validated {@code StompDevicePrincipal},
     * never from client-supplied data.
     */
    public void linkAuthenticatedSession(String simpSessionId, UUID serverSessionId) {
        serverSessionBySimpSession.put(simpSessionId, serverSessionId);
        simpSessionsByServerSession
                .computeIfAbsent(serverSessionId, key -> ConcurrentHashMap.newKeySet())
                .add(simpSessionId);
    }

    void unlinkTransportSession(String simpSessionId) {
        transportSessions.remove(simpSessionId);
        UUID serverSessionId = serverSessionBySimpSession.remove(simpSessionId);
        if (serverSessionId != null) {
            Set<String> remaining = simpSessionsByServerSession.get(serverSessionId);
            if (remaining != null) {
                remaining.remove(simpSessionId);
                if (remaining.isEmpty()) {
                    simpSessionsByServerSession.remove(serverSessionId, remaining);
                }
            }
        }
    }

    /**
     * Closes every live connection bound to the given server session.
     * Safe to call repeatedly and for unknown sessions.
     */
    public void terminateServerSession(UUID serverSessionId) {
        Set<String> simpSessionIds = simpSessionsByServerSession.remove(serverSessionId);
        if (simpSessionIds == null || simpSessionIds.isEmpty()) {
            return;
        }
        int closed = 0;
        for (String simpSessionId : simpSessionIds) {
            serverSessionBySimpSession.remove(simpSessionId);
            WebSocketSession session = transportSessions.remove(simpSessionId);
            if (session != null) {
                try {
                    if (session.isOpen()) {
                        session.close(CloseStatus.POLICY_VIOLATION);
                    }
                    closed++;
                } catch (Exception e) {
                    log.debug("WebSocket termination failed sessionId={}", serverSessionId);
                }
            }
        }
        log.info("Terminated {} websocket connection(s) for revoked session", closed);
    }

    /**
     * Terminates connections only after the surrounding revocation
     * transaction commits. Revocation must be durable before live
     * connections are torn down, and termination itself is not part of
     * the database transaction.
     */
    public void terminateAfterCommit(Collection<UUID> serverSessionIds) {
        if (serverSessionIds == null || serverSessionIds.isEmpty()) {
            return;
        }
        List<UUID> ids = List.copyOf(serverSessionIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ids.forEach(StompConnectionRegistry.this::terminateServerSession);
                }
            });
        } else {
            ids.forEach(this::terminateServerSession);
        }
    }
}
