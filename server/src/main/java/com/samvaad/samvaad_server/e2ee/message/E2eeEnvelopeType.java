package com.samvaad.samvaad_server.e2ee.message;

/**
 * Frozen wire envelope classification, mirrored from the client
 * {@code OutboundEnvelope.envelopeType} contract. Persisted exactly as
 * supplied; the server never inspects the ciphertext to determine it.
 */
public enum E2eeEnvelopeType {
    PREKEY_INIT,
    RATCHET
}
