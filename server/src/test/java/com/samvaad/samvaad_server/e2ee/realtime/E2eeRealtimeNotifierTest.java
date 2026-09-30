package com.samvaad.samvaad_server.e2ee.realtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.samvaad.samvaad_server.e2ee.dto.E2eeCiphertextItemDto;

@ExtendWith(MockitoExtension.class)
class E2eeRealtimeNotifierTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private E2eeRealtimeNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new E2eeRealtimeNotifier(messagingTemplate);
    }

    private E2eeCiphertextItemDto item(UUID messageId) {
        E2eeCiphertextItemDto dto = new E2eeCiphertextItemDto();
        dto.setMessageId(messageId);
        dto.setConversationId(UUID.randomUUID());
        dto.setSequenceNumber(1L);
        dto.setSenderUserId(UUID.randomUUID());
        dto.setSenderDeviceId(UUID.randomUUID());
        dto.setEnvelopeType("RATCHET");
        dto.setCiphertext("b3BhcXVlLWJ5dGVz");
        dto.setServerTimestamp(LocalDateTime.now());
        return dto;
    }

    @Test
    void deliversEachItemToItsOwnDeviceDestination() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        E2eeCiphertextItemDto firstItem = item(UUID.randomUUID());
        E2eeCiphertextItemDto secondItem = item(UUID.randomUUID());

        notifier.deliver(List.of(
                new E2eeRealtimeDelivery(first, firstItem),
                new E2eeRealtimeDelivery(second, secondItem)));

        then(messagingTemplate).should()
                .convertAndSend(eq("/topic/devices/" + first), eq(firstItem));
        then(messagingTemplate).should()
                .convertAndSend(eq("/topic/devices/" + second), eq(secondItem));
        then(messagingTemplate).shouldHaveNoMoreInteractions();
    }

    @Test
    void brokerFailureForOneDeviceDoesNotFailOthers() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        E2eeCiphertextItemDto failingItem = item(UUID.randomUUID());
        E2eeCiphertextItemDto healthyItem = item(UUID.randomUUID());
        doThrow(new IllegalStateException("broker down")).when(messagingTemplate)
                .convertAndSend(eq("/topic/devices/" + failing), eq(failingItem));

        assertDoesNotThrow(() -> notifier.deliver(List.of(
                new E2eeRealtimeDelivery(failing, failingItem),
                new E2eeRealtimeDelivery(healthy, healthyItem))));

        then(messagingTemplate).should()
                .convertAndSend(eq("/topic/devices/" + healthy), eq(healthyItem));
    }
}
