package com.samvaad.samvaad_server.e2ee.crypto;

import com.samvaad.samvaad_server.e2ee.exception.InvalidKeyMaterialException;
import org.springframework.stereotype.Component;

/**
 * Default envelope-only validator. Checks required presence (non-null,
 * non-empty) and a generous per-blob transport/storage bound that exists
 * solely to reject abusive payloads early. The bound is NOT a cryptographic
 * format claim: it is deliberately far above any plausible V1 key encoding,
 * and the exact protocol-specific validation belongs to the future
 * Signal-backed adapter behind the same interface.
 */
@Component
public class TransportKeyMaterialValidator implements KeyMaterialEnvelopeValidator {

    /**
     * Generous abuse bound per key blob. NOT a key-format rule; protocol
     * semantics are validated only by the future adapter.
     */
    public static final int MAX_KEY_BLOB_BYTES = 4096;

    @Override
    public void validateDeviceIdentityKey(byte[] encoded) {
        check("deviceIdentityPublicKey", encoded);
    }

    @Override
    public void validateSignedPrekey(byte[] encoded) {
        check("signedPrekey", encoded);
    }

    @Override
    public void validateSignedPrekeySignature(byte[] encoded) {
        check("signedPrekeySignature", encoded);
    }

    @Override
    public void validateOneTimePrekey(byte[] encoded) {
        check("publicKey", encoded);
    }

    private void check(String field, byte[] encoded) {
        if (encoded == null || encoded.length == 0) {
            throw new InvalidKeyMaterialException(field + " must be present and non-empty");
        }
        if (encoded.length > MAX_KEY_BLOB_BYTES) {
            throw new InvalidKeyMaterialException(field + " exceeds the transport bound");
        }
    }
}
