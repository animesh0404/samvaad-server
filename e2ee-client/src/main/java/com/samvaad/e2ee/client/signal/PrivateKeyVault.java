package com.samvaad.e2ee.client.signal;

import java.util.Set;
import java.util.UUID;

/**
 * Durable custody for sealed private-key material, owned by the crypto
 * adapter layer — never by the Samvaad service, store, transport, or wire
 * layers, which continue to see only opaque {@code SealedPrivateHandle}s.
 *
 * <p>The vault deals exclusively in opaque byte blobs keyed by handle UUID
 * plus a {@link KeyKind} tag. It never interprets the material (it cannot
 * tell Signal keys from any other bytes); serialization stays with the
 * adapter that sealed them. Raw private-key bytes therefore never cross
 * any Samvaad-owned contract: they travel only between the adapter and
 * this vault, encrypted at rest by the implementation.
 *
 * <p>Threading/ownership mirrors the client store boundary: single-process
 * ownership, write-through durability. Implementations fail closed
 * (unchecked) on unlock failures, tampering, version mismatch, or use
 * after close.
 */
public interface PrivateKeyVault {

    /** Opaque key-material category, also used to locate lone keys. */
    enum KeyKind {
        IDENTITY,
        SIGNED,
        OTPK,
        KYBER
    }

    /**
     * One decrypted entry. The plaintext array is a fresh copy owned by the
     * caller, which should zero it after rebuilding its key objects.
     */
    record SealedEntry(KeyKind kind, byte[] plaintext) {
        public SealedEntry {
            java.util.Objects.requireNonNull(kind, "kind");
            java.util.Objects.requireNonNull(plaintext, "plaintext");
        }
    }

    /** Persists (or atomically replaces) the sealed entry for a handle. */
    void store(UUID handleId, KeyKind kind, byte[] plaintext);

    /**
     * Returns the decrypted entry, or null when no entry exists for the
     * handle. Tampering fails closed with an unchecked exception, never
     * with silent garbage.
     */
    SealedEntry load(UUID handleId);

    /** Forgets the entry; absent handles are a no-op. */
    void remove(UUID handleId);

    /** Handle ids currently held for a kind (open verifier excluded). */
    Set<UUID> handlesOfKind(KeyKind kind);

    /** Locks the vault: zeroes key material and refuses further operations. */
    void close();
}
