package com.samvaad.samvaad_server.e2ee.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Durable peer-identity trust verdicts.
 *
 * <p>Inputs: peer deviceId + adapter fingerprint string. Outputs: stored
 * verdict. First sighting records TRUSTED (TOFU); a later different
 * fingerprint for the same deviceId moves to PAUSED_KEY_CHANGED and stays
 * there until explicit accept/reject.
 *
 * <p>Ownership: sole owner of trust verdicts; session and service layers read
 * but never mutate directly.
 *
 * <p>Persistence: verdicts survive restarts; accept/reject are durable before
 * any re-establishment proceeds.
 *
 * <p>Security invariants: never auto-trust a changed identity; never derive
 * trust from friendship alone (friendship = eligibility, not verification).
 */
public interface TrustStore {

    /** TOFU record on first sighting, or verdict for a known device. */
    CryptoTypes.TrustRecord observe(UUID peerDeviceId, String fingerprint);

    Optional<CryptoTypes.TrustRecord> load(UUID peerDeviceId);

    /** Explicit user verification of the new identity; back to TRUSTED. */
    void acceptKeyChange(UUID peerDeviceId, String newFingerprint);

    /** Keep PAUSED; future sends for this device stay refused. */
    void rejectKeyChange(UUID peerDeviceId);

    /** Terminal revocation verdict; only set on explicit revocation. */
    void markRevoked(UUID peerDeviceId);
}
