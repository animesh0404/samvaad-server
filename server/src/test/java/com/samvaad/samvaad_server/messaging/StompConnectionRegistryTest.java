package com.samvaad.samvaad_server.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class StompConnectionRegistryTest {

    private StompConnectionRegistry registry;

    @Mock
    private WebSocketSession firstTransport;

    @Mock
    private WebSocketSession secondTransport;

    @BeforeEach
    void setUp() {
        registry = new StompConnectionRegistry();
    }

    private void givenTransport(WebSocketSession transport, String simpSessionId) {
        given(transport.getId()).willReturn(simpSessionId);
    }

    private void givenOpenTransport(WebSocketSession transport, String simpSessionId) {
        givenTransport(transport, simpSessionId);
        given(transport.isOpen()).willReturn(true);
    }

    @Test
    void terminateClosesAllConnectionsForSessionAndIsIdempotent() throws Exception {
        UUID serverSessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        givenOpenTransport(firstTransport, "simp-first");
        givenTransport(secondTransport, "simp-second");
        registry.trackTransportSession(firstTransport);
        registry.trackTransportSession(secondTransport);
        registry.linkAuthenticatedSession("simp-first", serverSessionId);
        registry.linkAuthenticatedSession("simp-second", otherSessionId);

        registry.terminateServerSession(serverSessionId);

        then(firstTransport).should().close(CloseStatus.POLICY_VIOLATION);
        then(secondTransport).should(never()).close(any());

        registry.terminateServerSession(serverSessionId);
        verify(firstTransport, times(1)).close(any());
    }

    @Test
    void terminateUnknownSessionIsSafeNoOp() {
        registry.terminateServerSession(UUID.randomUUID());
        then(firstTransport).shouldHaveNoInteractions();
    }

    @Test
    void terminateToleratesFailingCloseAndStillClosesOthers() throws Exception {
        UUID serverSessionId = UUID.randomUUID();
        givenOpenTransport(firstTransport, "simp-first");
        givenOpenTransport(secondTransport, "simp-second");
        registry.trackTransportSession(firstTransport);
        registry.trackTransportSession(secondTransport);
        registry.linkAuthenticatedSession("simp-first", serverSessionId);
        registry.linkAuthenticatedSession("simp-second", serverSessionId);
        doThrow(new IllegalStateException("already gone")).when(firstTransport).close(any());

        registry.terminateServerSession(serverSessionId);

        then(secondTransport).should().close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void transportCloseUnregistersSoTerminateIsNoOp() throws Exception {
        UUID serverSessionId = UUID.randomUUID();
        givenTransport(firstTransport, "simp-first");
        registry.trackTransportSession(firstTransport);
        registry.linkAuthenticatedSession("simp-first", serverSessionId);
        registry.unlinkTransportSession("simp-first");

        registry.terminateServerSession(serverSessionId);

        then(firstTransport).should(never()).close(any());
    }

    @Test
    void terminateAfterCommitWithoutTransactionTerminatesImmediately() throws Exception {
        UUID serverSessionId = UUID.randomUUID();
        givenOpenTransport(firstTransport, "simp-first");
        registry.trackTransportSession(firstTransport);
        registry.linkAuthenticatedSession("simp-first", serverSessionId);

        registry.terminateAfterCommit(List.of(serverSessionId));

        then(firstTransport).should().close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void terminateAfterCommitWithEmptyInputIsSafeNoOp() {
        registry.terminateAfterCommit(List.of());

        then(firstTransport).shouldHaveNoInteractions();
    }
}
