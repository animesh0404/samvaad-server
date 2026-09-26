package com.samvaad.samvaad_server.e2ee.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates lazy per-device session establishment + multi-device fan-out.
 *
 * <p>Frozen crash-safe state machine per (message, recipient) slot:
 *
 * <pre>
 * PENDING --persist slot--> CLAIM (network, idempotent claimRequestId)
 *   --bundle--> ESTABLISH (pure local adapter call)
 *   --blob--> persist SESSION_READY (durable commit BEFORE encrypt)
 *   --encrypt--> persist ENCRYPTED/COMMITTED (envelope + advanced session)
 *   --submit--> SUBMITTED --ack--> ACKED
 * </pre>
 *
 * <p>Retry/crash rules: resume with the SAME claimRequestId and SAME claimed
 * bundle (replayed claim); never consume a second OTPK for the same slot;
 * never create a second session for the same slot after SESSION_READY — reuse
 * the committed blob; failed submit does NOT roll the ratchet back and does
 * NOT re-encrypt: retry resubmits the identical committed bytes. Local
 * re-establishment before commit is allowed only with the SAME bundle and
 * only one envelope is ever submitted per slot.
 *
 * <p>Committed-slot precedence: a retry for a message with an already
 * COMMITTED slot replays the exact committed envelope before (and regardless
 * of) current directory/device/trust evaluation; later identity or trust
 * changes never re-encrypt a committed message.
 *
 * <p>Presence rules: directory miss = NOT_RETURNED_TRANSIENT (retain session
 * + trust, defer slot); only explicit revocation = terminal purge.
 *
 * <p>Inputs: plaintext-associated bytes (already-framed app payload), sender
 * device id, directory snapshot (ACTIVE only), explicit revocation set.
 * Outputs: per-device outcome map. Errors: per-device outcomes, never a bare
 * throw for a single bad device when others can proceed.
 */
public interface SamvaadCryptoService {

    /** Server transport seam for prekey claims (fake in tests, HTTP later). */
    interface ClaimClient {
        CryptoTypes.RecipientBundle claim(UUID recipientDeviceId, UUID claimRequestId)
                throws CryptoException.TransientException, CryptoException.ClaimFailedException;
    }

    /** Server transport seam for ciphertext submission (fake in tests). */
    interface SubmitClient {
        void submit(UUID messageRequestId, List<CryptoTypes.OutboundEnvelope> envelopes)
                throws CryptoException.TransientException;
    }

    /** Per-device send outcome. */
    enum DeviceOutcome {
        SENT,
        PAUSED_KEY_CHANGED,
        SKIPPED_REVOKED,
        DEFERRED_TRANSIENT,
        FAILED_CORRUPT
    }

    /** Result of one fan-out send. */
    record FanoutResult(UUID messageRequestId, Map<UUID, DeviceOutcome> outcomes) {
        public long sentCount() {
            return outcomes.values().stream().filter(o -> o == DeviceOutcome.SENT).count();
        }
    }

    /**
     * Lazy-establish (or reuse) one session per recipient device and encrypt
     * one envelope each, then submit as a single batch.
     *
     * @param messageRequestId client-generated idempotency key for the message
     * @param senderDeviceId   own device id
     * @param plaintextAssoc   framed plaintext-associated payload (content +
     *                         non-essential metadata already inside)
     * @param directoryActive  ACTIVE recipient devices from the directory
     * @param explicitlyRevoked peer devices explicitly revoked (server REVOKED
     *                         record or DEVICE_REVOKED reject)
     */
    FanoutResult sendToDevices(
            UUID messageRequestId,
            UUID senderDeviceId,
            byte[] plaintextAssoc,
            List<CryptoTypes.RecipientBundle> directoryActive,
            Set<UUID> explicitlyRevoked);

    /**
     * Decrypt one inbound envelope. The caller selects the path from the
     * wire {@link CryptoTypes.EnvelopeType} — never by trial-parsing:
     * PREKEY_INIT converges without destroying a concurrent outbound
     * session; RATCHET advances the committed session. Persists updated
     * blobs before returning; PREKEY_INIT commits the inbound session and
     * the OTPK consumption atomically via
     * {@link ClientCryptoStore#commitInboundEstablishment}.
     */
    byte[] decrypt(UUID senderDeviceId, UUID peerDeviceId, CryptoTypes.EnvelopeType kind, byte[] envelopeCiphertext);

    /**
     * Explicit user verification path after a key-change pause. Accepts the
     * canonical identity public key bytes (never a display string).
     */
    void acceptKeyChange(UUID peerDeviceId, byte[] newIdentityPublicKey);

    void rejectKeyChange(UUID peerDeviceId);

    /** Explicit revocation path: terminal purge of sessions + trust verdict. */
    void revokePeerDevice(UUID peerDeviceId);
}
