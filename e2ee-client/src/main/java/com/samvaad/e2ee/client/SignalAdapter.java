package com.samvaad.e2ee.client;

import java.util.UUID;

/**
 * Samvaad-owned seam over the concrete Signal/Sesame implementation.
 *
 * <p>Frozen contract for the fake slice. The real adapter (libsignal or
 * compatible) implements this same interface later; no caller outside this
 * interface may reference library types.
 *
 * <p>Inputs: opaque public bytes + sealed private handles issued by this
 * adapter. Outputs: opaque session blobs / ciphertext. Errors: adapter throws
 * {@link CryptoException.SessionCorruptException} for undecryptable/invalid
 * state, {@link CryptoException.ClaimFailedException} for bad bundles.
 *
 * <p>Ownership: the adapter owns all crypto-math mutable state transitions
 * for a single establish/encrypt/decrypt call, but owns NO durability — the
 * caller persists returned blobs via {@link SessionStore} before network I/O.
 *
 * <p>Persistence: adapter itself persists nothing. All returned blobs must be
 * treated as durable only after the caller commits them.
 *
 * <p>Security invariants: never returns private key bytes; never accepts raw
 * ratchet state from outside; verify() performs real signature verification
 * in the production adapter (fake: structural check only, documented in the
 * fake class).
 */
public interface SignalAdapter {

    /** Opaque handle to locally sealed private key material. No bytes escape. */
    interface SealedPrivateHandle {
        UUID handleId();
    }

    /** Locally generated identity (public bytes cross enrollment; priv stays sealed). */
    record LocalIdentity(byte[] identityPublicKey, SealedPrivateHandle identityPrivate) {
    }

    /** Locally generated signed prekey pair. */
    record SignedPrekeyPair(
            int prekeyId, byte[] publicKey, byte[] signature, SealedPrivateHandle privateHandle) {
    }

    /** Locally generated one-time prekey pair. */
    record OneTimePrekeyPair(int prekeyId, byte[] publicKey, SealedPrivateHandle privateHandle) {
    }

    /**
     * Locally generated last-resort Kyber prekey pair (ADR-0019 triple).
     *
     * <p>The public triple ({@code prekeyId}, {@code publicKey}, {@code
     * signature}) is what crosses enrollment into the server directory and
     * returns inside claimed bundles; the private half stays sealed. The
     * signature is the identity key's signature over the serialized Kyber
     * public key, verifiable by any V1 peer without library-specific calls
     * beyond the adapter.
     */
    record KyberPrekeyPair(
            int prekeyId, byte[] publicKey, byte[] signature, SealedPrivateHandle privateHandle) {
    }

    /** Result of outbound X3DH; blob must be durably committed before encrypt. */
    record EstablishedSession(byte[] sessionBlob, CryptoTypes.EstablishmentMode mode) {
    }

    /**
     * Result of one encrypt call; caller persists the updated session blob.
     *
     * <p>{@code envelopeType} is the authoritative wire type of the produced
     * bytes when non-null: libsignal repeats the prekey message (same OTPK
     * reference) until the peer's first reply advances the session, so a
     * reused session can still yield PREKEY_INIT bytes and only the producer
     * can classify them. Null defers to the Samvaad heuristic (fake/testing
     * adapters); real adapters must always report.
     */
    record EncryptResult(
            byte[] updatedSessionBlob,
            byte[] envelopeCiphertext,
            CryptoTypes.EnvelopeType envelopeType) {
    }

    /** Result of one decrypt call; caller persists the updated session blob. */
    record DecryptResult(
            byte[] updatedSessionBlob,
            byte[] plaintextAssoc,
            Integer consumedOneTimePrekeyIdOrNull) {
    }

    /**
     * Samvaad-owned lookup of sealed one-time-prekey private handles by
     * prekey ID, for inbound prekey-init processing.
     *
     * <p>The adapter parses the incoming envelope's referenced OTPK ID from
     * its own typed Signal fields (never from Samvaad-layer parsing) and
     * resolves exactly that handle through this callback. Resolution is a
     * peek: it must NOT consume. The caller consumes (forgets) the reported
     * {@link DecryptResult#consumedOneTimePrekeyIdOrNull} exactly once after
     * the inbound session is durably committed.
     *
     * <p>Unknown or already-consumed IDs fail closed with {@link
     * CryptoException.ClaimFailedException}; the resolver must never
     * substitute a different OTPK, so replay of a consumed OTPK is a
     * deterministic rejection, never a second consumption.
     */
    @FunctionalInterface
    interface OtpkResolver {
        SealedPrivateHandle resolve(int oneTimePrekeyId);
    }

    LocalIdentity generateIdentity();

    SignedPrekeyPair generateSignedPrekey(SealedPrivateHandle identityPrivate, int prekeyId);

    OneTimePrekeyPair generateOneTimePrekey(int prekeyId);

    /**
     * Generates this device's long-lived last-resort Kyber pair. The
     * selected libsignal line mandates Kyber material in every session
     * bundle (X3DH-only bundles are rejected), so a V1 device cannot
     * establish or receive sessions without one; one-time Kyber pools
     * remain deferred.
     */
    KyberPrekeyPair generateKyberPrekey(SealedPrivateHandle identityPrivate, int prekeyId);

    /**
     * Stable display fingerprint of an identity public key, for human
     * out-of-band verification UI only. Trust decisions compare canonical
     * key bytes, never this string.
     */
    String fingerprint(byte[] identityPublicKey);

    /** Verify signed-prekey signature against the identity key. */
    boolean verifySignedPrekey(byte[] identityPublicKey, byte[] signedPrekey, byte[] signature);

    /**
     * Outbound X3DH against a claimed bundle. Pure local call; consumes no
     * server state. Caller must have already persisted the slot as CLAIMED
     * with the bundle and must persist the returned blob before encrypting.
     */
    EstablishedSession establishOutbound(
            SealedPrivateHandle ownIdentityPrivate, CryptoTypes.RecipientBundle bundle);

    /**
     * Inbound processing of a PREKEY_INIT envelope. Must NOT destroy a
     * concurrently existing outbound session for the same peer: implementations
     * converge (Sesame-style) so both directions remain decryptable.
     *
     * <p>The adapter extracts the referenced one-time-prekey ID from the
     * envelope itself and resolves exactly that private handle via {@code
     * otpks}; a bundle without one-time-prekey material (signed-prekey
     * fallback) resolves nothing and reports a null consumed ID. Raw private
     * key bytes never cross this boundary in either direction.
     */
    DecryptResult decryptPrekeyInit(
            SealedPrivateHandle ownIdentityPrivate,
            SealedPrivateHandle ownSignedPrivate,
            OtpkResolver otpks,
            byte[] currentSessionBlobOrNull,
            byte[] envelopeCiphertext);

    EncryptResult encrypt(byte[] sessionBlob, byte[] plaintextAssoc);

    DecryptResult decrypt(byte[] sessionBlob, byte[] envelopeCiphertext);
}
