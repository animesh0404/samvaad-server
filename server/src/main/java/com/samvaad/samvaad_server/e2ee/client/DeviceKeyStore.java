package com.samvaad.samvaad_server.e2ee.client;

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

    Optional<SignalAdapter.SealedPrivateHandle> oneTimePrivate(int prekeyId);

    /** Forget an OTPK private after its public counterpart is consumed. */
    void forgetOneTimePrivate(int prekeyId);
}
