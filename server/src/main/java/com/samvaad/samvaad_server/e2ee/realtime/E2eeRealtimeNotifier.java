package com.samvaad.samvaad_server.e2ee.realtime;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Best-effort realtime fan-out for committed E2EE ciphertext.
 *
 * <p>Delivery-only: persists nothing, acknowledges nothing, decrypts
 * nothing. Each delivery carries only the envelope persisted for that
 * exact recipient device, sent to {@code /topic/devices/{deviceId}} —
 * the same device channel authorized by the STOMP interceptor. A device
 * with no connected subscriber simply receives nothing; its durable
 * mailbox entry remains the fallback. A delivery failure never fails
 * the originating HTTPS submission.
 */
@Component
public class E2eeRealtimeNotifier {

    /**
     * Must match the device channel authorized by the STOMP interceptor.
     * Routing stays server-side: the destination is derived from persisted
     * envelope recipients, never from client-supplied data.
     */
    static final String DEVICE_TOPIC_PREFIX = "/topic/devices/";

    private static final Logger log = LoggerFactory.getLogger(E2eeRealtimeNotifier.class);

    private final SimpMessagingTemplate messagingTemplate;

    public E2eeRealtimeNotifier(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void deliver(List<E2eeRealtimeDelivery> deliveries) {
        for (E2eeRealtimeDelivery delivery : deliveries) {
            try {
                messagingTemplate.convertAndSend(
                        DEVICE_TOPIC_PREFIX + delivery.recipientDeviceId(), delivery.item());
            } catch (Exception e) {
                // Persistence already committed and the mailbox entry
                // remains; realtime is only a hint.
                log.warn("Realtime delivery failed messageId={} deviceId={}",
                        delivery.item().getMessageId(), delivery.recipientDeviceId(), e);
            }
        }
    }
}
