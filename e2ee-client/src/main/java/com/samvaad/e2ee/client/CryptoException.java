package com.samvaad.e2ee.client;

/**
 * Crypto error taxonomy. All failures are explicit so callers can distinguish
 * terminal (revoked), trust-gated (paused), retryable-transient, and corrupt
 * states without string matching.
 */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    /** Trust gate: peer identity changed; encrypt paused until verified. */
    public static final class KeyChangePausedException extends CryptoException {
        public KeyChangePausedException(String message) {
            super(message);
        }
    }

    /** Terminal: device explicitly revoked; purge sessions, skip fan-out. */
    public static final class DeviceRevokedException extends CryptoException {
        public DeviceRevokedException(String message) {
            super(message);
        }
    }

    /** Retryable: directory miss, network failure, claim conflict. */
    public static final class TransientException extends CryptoException {
        public TransientException(String message) {
            super(message);
        }
    }

    /** Local session blob invalid; quarantine and re-establish. */
    public static final class SessionCorruptException extends CryptoException {
        public SessionCorruptException(String message) {
            super(message);
        }
    }

    /** Claim/envelope validation failure; do not retry blindly. */
    public static final class ClaimFailedException extends CryptoException {
        public ClaimFailedException(String message) {
            super(message);
        }
    }
}
