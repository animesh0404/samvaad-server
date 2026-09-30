package com.samvaad.samvaad_server.e2ee.realtime;

import java.util.UUID;

import com.samvaad.samvaad_server.e2ee.dto.E2eeCiphertextItemDto;

/**
 * Immutable realtime delivery unit: one persisted per-device ciphertext
 * item plus the device it was persisted for. Built from committed state
 * only, after the persistence transaction returns.
 */
public record E2eeRealtimeDelivery(UUID recipientDeviceId, E2eeCiphertextItemDto item) {
}
