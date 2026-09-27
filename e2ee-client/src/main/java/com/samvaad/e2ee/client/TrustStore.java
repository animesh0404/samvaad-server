package com.samvaad.e2ee.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Durable peer-identity trust verdicts.
 *
 * <p>Inputs: peer deviceId + canonical identity public key bytes. Outputs:
 * stored verdict. First sighting records TRUSTED (TOFU); a later
 * byte-different identity key for the same deviceId moves to
 * PAUSED_KEY_CHANGED and stays there until explicit accept/reject. Trust
 * comparisons are byte-equality on the canonical identity key only;
 * adapter display fingerprints are never compared here (see ADR-0021).
 *
 * <p>Ownership: sole owner of trust verdicts; session and service layers read
 * but never mutate directly.
 *
 * <p>Persistence: verdicts survive restarts; accept/reject are durable before
 * any re-establishment proceeds.
 *
 * <p>Security invariants: never auto-trust a changed identity; never derive
 * trust from friendship alone (friendship = eligibility, not verification);
 * private keys never enter this contract.
 */
public interface TrustStore {

    /** TOFU record on first sighting, or verdict for a known device. */
    CryptoTypes.TrustRecord observe(UUID peerDeviceId, byte[] identityPublicKey);

    Optional<CryptoTypes.TrustRecord> load(UUID peerDeviceId);

    /**
     * Explicit user verification of the new identity; back to TRUSTED storing
     * the accepted canonical key bytes.
     */
    void acceptKeyChange(UUID peerDeviceId, byte[] newIdentityPublicKey);

    /** Keep PAUSED; future sends for this device stay refused. */
    void rejectKeyChange(UUID peerDeviceId);

    /** Terminal revocation verdict; only set on explicit revocation. */
    void markRevoked(UUID peerDeviceId);
}
