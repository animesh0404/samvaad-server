package com.samvaad.e2ee.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable store for peer sessions AND per-message outbound slots.
 *
 * <p>Inputs: adapter-opaque session blobs, claimed bundles, slot transitions.
 * Outputs: committed records or empty. Errors: {@link IllegalArgumentException}
 * on conflicting commits for the same slot with different bundles.
 *
 * <p>Ownership: sole owner of durability for crypto-operation state. The
 * service performs network I/O only after the corresponding persist call
 * returns.
 *
 * <p>Persistence requirements (production): atomic write per save; slots and
 * sessions flushed BEFORE claim-network-call returns are acted on and BEFORE
 * ciphertext submission. Crash recovery = reload slots != ACKED and resume
 * with the SAME claimRequestId/bundle (see service state machine).
 *
 * <p>Security invariants: blobs are opaque; the store never interprets,
 * logs, or exports them.
 */
public interface SessionStore {

    // ---- sessions (one committed row per peer device, at most) ----

    void saveSession(CryptoTypes.SessionRecord record);

    Optional<CryptoTypes.SessionRecord> loadSession(UUID peerDeviceId);

    void deleteSession(UUID peerDeviceId);

    List<CryptoTypes.SessionRecord> allSessions();

    // ---- outbound slots (one row per message x recipient, crash-safe) ----

    /**
     * Upsert a slot. Re-saving with the same (messageRequestId, recipient,
     * claimRequestId) is allowed for forward state transitions only; changing
     * the claimed bundle for an existing slot is rejected to prevent OTPK
     * confusion.
     *
     * <p>Structural rule: implementations MUST reject slots in state {@code
     * COMMITTED}. COMMITTED rows (which always carry ciphertext) are written
     * exclusively by {@link #commitOutboundCiphertext}, so no normal code
     * path can persist a committed slot without its matching advanced
     * session. Later transitions of an already-committed slot (e.g. {@code
     * ACKED}, which retains the ciphertext) remain valid single writes.
     */
    void saveSlot(CryptoTypes.OutboundSlot slot);

    Optional<CryptoTypes.OutboundSlot> loadSlot(UUID messageRequestId, UUID recipientDeviceId);

    List<CryptoTypes.OutboundSlot> slotsForMessage(UUID messageRequestId);

    /** Un-acked slots pending recovery after restart. */
    List<CryptoTypes.OutboundSlot> pendingSlots();

    /**
     * Durable atomic commit of one outbound encrypt result: the advanced
     * session blob AND the {@code COMMITTED} slot carrying the corresponding
     * ciphertext become visible together, or neither does.
     *
     * <p>Callers MUST use this boundary (never a separate
     * {@link #saveSession} + {@link #saveSlot} pair) for the
     * encrypt-to-COMMITTED transition. Otherwise a crash between the two
     * writes leaves a durable session-advanced/ciphertext-missing state, and
     * recovery re-encryption on the advanced ratchet diverges from any
     * redelivered bytes.
     *
     * <p>Contract: {@code committedSlot} must be in state {@code COMMITTED}
     * with a non-null ciphertext; {@code advancedSession} must belong to the
     * same peer and advance the previously committed session's encrypt
     * counter by exactly one. Violations fail with {@link
     * IllegalArgumentException}; a store that cannot make both writes atomic
     * must fail rather than persist a partial commit.
     */
    void commitOutboundCiphertext(
            CryptoTypes.SessionRecord advancedSession, CryptoTypes.OutboundSlot committedSlot);
}
