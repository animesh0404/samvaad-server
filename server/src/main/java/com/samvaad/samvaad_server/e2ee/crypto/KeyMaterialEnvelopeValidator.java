package com.samvaad.samvaad_server.e2ee.crypto;

/**
 * Samvaad-owned seam for public key-material validation (ADR 0018 crypto
 * implementation boundary). Domain, persistence, and transport code depend
 * only on this interface, never on libsignal-specific types.
 *
 * <p>This foundation slice validates ONLY properties that can honestly be
 * checked without the real Signal implementation: required field presence,
 * non-null, non-empty, structurally valid transport encoding, and generous
 * transport/storage size bounds. It performs no cryptographic validation: no
 * curve or key-format rules, no invented fixed lengths, no parsing of Signal
 * key structures, no signature verification, no fake cryptographic
 * operations.
 *
 * <p>The future Signal-backed adapter implements this same interface with
 * protocol-specific validation. Swapping the implementation must not require
 * changes to the E2EE domain model.
 */
public interface KeyMaterialEnvelopeValidator {

    void validateDeviceIdentityKey(byte[] encoded);

    void validateSignedPrekey(byte[] encoded);

    void validateSignedPrekeySignature(byte[] encoded);

    void validateOneTimePrekey(byte[] encoded);
}
