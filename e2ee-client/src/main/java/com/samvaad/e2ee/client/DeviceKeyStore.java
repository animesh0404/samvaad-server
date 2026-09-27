package com.samvaad.e2ee.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Owns the local device's key material.
 *
 * <p>Inputs: adapter-generated pairs at provisioning time. Outputs: public
 * bytes for enrollment/upload, sealed handles for adapter calls.
 * Errors: {@link IllegalStateException} when the device is not provisioned.
 *
 * <p>Ownership: sole owner of sealed private handles; never hands out bytes
 * for private material.
 *
 * <p>Persistence: production backends persist sealed material in the platform
 * store (Keystore/file/IndexedDB) before returning from provisioning;
 * in-memory fake keeps it in a map. Sealed handles are stable across restarts
 * of the owning process only if the store is durable.
 *
 * <p>Security invariants: no method returns private key bytes; public getters
 * return defensive copies.
 */
public interface DeviceKeyStore {

    /** Provision a fresh identity + signed prekey for this installation. */
    void provision(SignalAdapter.LocalIdentity identity, SignalAdapter.SignedPrekeyPair signedPrekey);

    boolean isProvisioned();

    UUID ownDeviceId();

    int registrationId();

    byte[] identityPublicKey();

    SignalAdapter.SealedPrivateHandle identityPrivate();

    SignalAdapter.SignedPrekeyPair signedPrekey();

    /** Register a locally generated OTPK private handle for later inbound use. */
    void putOneTimePrivate(int prekeyId, SignalAdapter.SealedPrivateHandle privateHandle);

    /**
     * Peek at the sealed private handle for one OTPK ID without consuming
     * it. Used by the inbound prekey-init resolver: lookup only, consumption
     * happens via {@link #forgetOneTimePrivate} after the inbound session is
     * durably committed.
     */
    Optional<SignalAdapter.SealedPrivateHandle> oneTimePrivate(int prekeyId);

    /**
     * Resolve-or-fail for inbound establishment: returns the sealed handle
     * for exactly the requested OTPK ID, or fails closed with {@link
     * CryptoException.ClaimFailedException} when the ID is unknown or
     * already consumed. Never substitutes a different OTPK.
     */
    default SignalAdapter.SealedPrivateHandle requireOneTimePrivate(int prekeyId) {
        return oneTimePrivate(prekeyId).orElseThrow(() -> new CryptoException.ClaimFailedException(
                "unknown or already-consumed one-time prekey: " + prekeyId));
    }

    /**
     * Forget an OTPK private after its public counterpart is consumed.
     *
     * <p>Exactly-once protocol: the inbound path forgets the adapter-reported
     * consumed ID exactly once per successful establishment. Forgetting an
     * absent ID is a no-op. Replay of an already-consumed ID fails at
     * resolution time and must never consume another OTPK.
     */
    void forgetOneTimePrivate(int prekeyId);
}
