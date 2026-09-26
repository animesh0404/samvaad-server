package com.samvaad.samvaad_server.e2ee.client;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Frozen V1 client-side crypto schemas (design-definition slice).
 *
 * <p>All Signal-library-specific material is opaque here: public keys are
 * opaque byte blobs, private keys never appear (see {@link SignalAdapter}
 * sealed handles), session blobs are adapter-opaque bytes that stores persist
 * without interpreting.
 *
 * <p>Locked rules encoded: lazy establishment only, no speculative X3DH,
 * per-recipient-device sessions/envelopes, server never creates sessions.
 */
public final class CryptoTypes {

    private CryptoTypes() {
    }

    /** Wire format version for {@link OutboundEnvelope}. */
    public static final int ENVELOPE_FORMAT_VERSION = 1;

    /** Crypto suite identifier recorded in every envelope. */
    public static final String CRYPTO_SUITE = "signal-sesame-v1";

    /** Local store format version. Bump requires a migration path. */
    public static final int STORE_FORMAT_VERSION = 1;

    /** Trust state of a peer device identity. */
    public enum TrustState {
        /** Never seen; first contact proceeds with TOFU recording. */
        UNKNOWN,
        /** Fingerprint matches the trusted record. */
        TRUSTED,
        /** Same deviceId presents a different identity key; encrypt paused. */
        PAUSED_KEY_CHANGED,
        /** Explicit revocation only; terminal until a NEW deviceId enrolls. */
        REVOKED_EXPLICIT
    }

    /** Local Signal/Sesame session state per peer device. */
    public enum LocalSessionState {
        /** No local session; needs lazy establishment on next send. */
        NO_SESSION,
        /** Bundle claimed, adapter establishment not yet durably committed. */
        ESTABLISHING,
        /** Committed session usable for encrypt/decrypt. */
        READY,
        /** Paused by trust (key change); encrypt refused, decrypt allowed. */
        PAUSED,
        /** Adapter reported corrupt/invalid; quarantined, needs re-establish. */
        CORRUPT
    }

    /**
     * How the peer device appeared to the sender at send time. A directory
     * miss is {@link #NOT_RETURNED_TRANSIENT} and must NOT be treated as
     * revocation or deletion.
     */
    public enum PeerPresence {
        /** Returned in the ACTIVE directory for this send. */
        ACTIVE,
        /** Known locally but absent from this directory snapshot. */
        NOT_RETURNED_TRANSIENT,
        /** Explicitly revoked (server REVOKED record or DEVICE_REVOKED reject). */
        REVOKED_EXPLICIT
    }

    /** Crash-safe per-recipient send slot state. */
    public enum OutboundSlotState {
        PENDING,
        CLAIMED,
        SESSION_READY,
        ENCRYPTED,
        COMMITTED,
        SUBMITTED,
        ACKED,
        FAILED_PAUSED,
        FAILED_REVOKED,
        FAILED_TRANSIENT
    }

    /** Whether session establishment consumed an OTPK or fell back. */
    public enum EstablishmentMode {
        WITH_ONE_TIME_PREKEY,
        SIGNED_PREKEY_FALLBACK
    }

    /**
     * Frozen wire envelope classification (PART C). No trial-parsing: the
     * sender sets the type, the recipient selects the parser from it.
     *
     * <p>Mapping, verified against libsignal 0.103.x:
     * <ul>
     *   <li>{@link #PREKEY_INIT} → Signal {@code PreKeySignalMessage} (type 3)
     *       → adapter inbound prekey init (converges, never destroys a
     *       concurrent outbound session).</li>
     *   <li>{@link #RATCHET} → Signal {@code SignalMessage} (type 2) →
     *       adapter ratchet decrypt on the committed session.</li>
     * </ul>
     */
    public enum EnvelopeType {
        PREKEY_INIT,
        RATCHET
    }

    /**
     * Frozen Signal address model (PART C): name = immutable Samvaad
     * {@code userId}, device = server-assigned {@code signalDeviceId}.
     * The Samvaad UUID device id is never used as the Signal integer.
     */
    public record SignalAddress(UUID userId, int signalDeviceId) {
        public SignalAddress {
            Objects.requireNonNull(userId, "userId");
            if (signalDeviceId < 1) {
                throw new IllegalArgumentException("signalDeviceId starts at 1");
            }
        }
    }

    /**
     * Public bundle for one recipient device (maps to server
     * RecipientDeviceDto + ClaimPrekeyResponseDto; OTPK body present only
     * after claim). Carries everything the adapter needs to construct a
     * libsignal PreKeyBundle: address (userId + server-assigned
     * signalDeviceId), registration id, identity, signed EC prekey,
     * optional EC one-time prekey, and the mandatory last-resort Kyber
     * triple. No private material, ever.
     */
    public record RecipientBundle(
            UUID deviceId,
            UUID userId,
            int signalDeviceId,
            int registrationId,
            byte[] identityPublicKey,
            int signedPrekeyId,
            byte[] signedPrekey,
            byte[] signedPrekeySignature,
            Integer oneTimePrekeyId,
            byte[] oneTimePrekey,
            Integer kyberPrekeyId,
            byte[] kyberPrekey,
            byte[] kyberPrekeySignature) {
        public RecipientBundle {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(userId, "userId");
            if (signalDeviceId < 1) {
                throw new IllegalArgumentException("signalDeviceId starts at 1");
            }
            identityPublicKey = copy(identityPublicKey);
            signedPrekey = copy(signedPrekey);
            signedPrekeySignature = copy(signedPrekeySignature);
            oneTimePrekey = oneTimePrekey == null ? null : copy(oneTimePrekey);
            kyberPrekey = kyberPrekey == null ? null : copy(kyberPrekey);
            kyberPrekeySignature = kyberPrekeySignature == null ? null : copy(kyberPrekeySignature);
        }

        public boolean hasOneTimePrekey() {
            return oneTimePrekeyId != null && oneTimePrekey != null;
        }

        public boolean hasKyber() {
            return kyberPrekeyId != null && kyberPrekey != null && kyberPrekeySignature != null;
        }

        /** Frozen Signal address for this bundle (PART C). */
        public SignalAddress address() {
            return new SignalAddress(userId, signalDeviceId);
        }
    }

    /**
     * Exact V1 wire schema for one per-device outbound envelope — seven
     * fields, nothing more. Ciphertext is adapter-opaque; the Samvaad
     * layer routes on the header. Registration ids, prekey ids, and the
     * establishment mode are deliberately absent: they live inside the
     * Signal material or local slot state (see field table in the
     * wire-contract freeze).
     */
    public record OutboundEnvelope(
            int formatVersion,
            String cryptoSuite,
            EnvelopeType envelopeType,
            UUID messageRequestId,
            UUID senderDeviceId,
            UUID recipientDeviceId,
            byte[] envelopeCiphertext) {
        public OutboundEnvelope {
            Objects.requireNonNull(envelopeType, "envelopeType");
            Objects.requireNonNull(messageRequestId, "messageRequestId");
            Objects.requireNonNull(senderDeviceId, "senderDeviceId");
            Objects.requireNonNull(recipientDeviceId, "recipientDeviceId");
            envelopeCiphertext = copy(envelopeCiphertext);
            if (formatVersion != ENVELOPE_FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported envelope version");
            }
            if (!CRYPTO_SUITE.equals(cryptoSuite)) {
                throw new IllegalArgumentException("unsupported crypto suite");
            }
        }
    }

    /** Durable per-recipient send slot for crash-safe retry. */
    public record OutboundSlot(
            UUID messageRequestId,
            UUID senderDeviceId,
            UUID recipientDeviceId,
            UUID claimRequestId,
            OutboundSlotState state,
            EstablishmentMode establishmentMode,
            EnvelopeType envelopeType,
            RecipientBundle claimedBundle,
            byte[] envelopeCiphertext) {
        public OutboundSlot {
            Objects.requireNonNull(messageRequestId, "messageRequestId");
            Objects.requireNonNull(senderDeviceId, "senderDeviceId");
            Objects.requireNonNull(recipientDeviceId, "recipientDeviceId");
            Objects.requireNonNull(claimRequestId, "claimRequestId");
            Objects.requireNonNull(state, "state");
            envelopeCiphertext = envelopeCiphertext == null ? null : copy(envelopeCiphertext);
        }
    }

    /** Durable local session record; blob is adapter-opaque. */
    public record SessionRecord(
            UUID peerDeviceId,
            byte[] peerIdentityPublicKey,
            int peerRegistrationId,
            LocalSessionState state,
            EstablishmentMode establishedVia,
            byte[] sessionBlob,
            long encryptCounter,
            long decryptCounter) {
        public SessionRecord {
            Objects.requireNonNull(peerDeviceId, "peerDeviceId");
            Objects.requireNonNull(state, "state");
            peerIdentityPublicKey = peerIdentityPublicKey == null ? null : copy(peerIdentityPublicKey);
            sessionBlob = sessionBlob == null ? null : copy(sessionBlob);
        }
    }

    /**
     * Durable trust record. The canonical identity public key bytes are the
     * authoritative trust primitive: comparisons are byte-equality on these
     * bytes only. Human-verifiable fingerprints are a derived display form
     * (see {@link SignalAdapter#fingerprint(byte[])} and ADR-0021) and are
     * never persisted or compared here. Private keys never appear in this
     * contract.
     */
    public record TrustRecord(UUID peerDeviceId, byte[] identityPublicKey, TrustState state) {
        public TrustRecord {
            Objects.requireNonNull(peerDeviceId, "peerDeviceId");
            Objects.requireNonNull(state, "state");
            identityPublicKey =
                    identityPublicKey == null ? null : Arrays.copyOf(identityPublicKey, identityPublicKey.length);
        }
    }

    /**
     * Deterministic claim idempotency key. Same
     * (messageRequestId, senderDeviceId, recipientDeviceId) always yields the
     * same UUID, so retries/crash recovery replay the SAME server claim and
     * never consume a second OTPK. A new message yields a new key.
     */
    public static UUID deriveClaimRequestId(
            UUID messageRequestId, UUID senderDeviceId, UUID recipientDeviceId) {
        Objects.requireNonNull(messageRequestId, "messageRequestId");
        Objects.requireNonNull(senderDeviceId, "senderDeviceId");
        Objects.requireNonNull(recipientDeviceId, "recipientDeviceId");
        String seed = "samvaad-v1-claim:" + messageRequestId + ":" + senderDeviceId + ":" + recipientDeviceId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] copy(byte[] in) {
        if (in == null) {
            return null;
        }
        return Arrays.copyOf(in, in.length);
    }
}
