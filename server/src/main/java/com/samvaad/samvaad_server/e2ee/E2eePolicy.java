package com.samvaad.samvaad_server.e2ee;

/**
 * Locked V1 E2EE operational policy (ADR 0018). These numbers are Samvaad V1
 * policy, not cryptographic requirements; they stay constants rather than
 * database or API inputs so a client can never negotiate different values.
 */
public final class E2eePolicy {

    private E2eePolicy() {
    }

    /** Maximum enrolled (non-REVOKED) cryptographic devices per account. */
    public static final int MAX_ENROLLED_DEVICES = 5;

    /**
     * Maximum decoded opaque ciphertext bytes per submitted envelope. This is
     * the authoritative resource/security bound: V1 carries message-text
     * ciphertext only (attachments are out of scope), and Signal-family text
     * ciphertext is KB-scale, so 64 KiB is generous headroom that still keeps
     * heap allocation, BYTEA rows, and realtime broadcast frames trivial.
     */
    public static final int MAX_CIPHERTEXT_BYTES_PER_ENVELOPE = 65_536;

    /**
     * Maximum envelopes in one message submission. Deliberately above the
     * 5-device account maximum (one logical message addresses a single
     * recipient user), so legitimate multi-device fan-out always fits while
     * list-bomb submissions are rejected before decode and persistence.
     */
    public static final int MAX_ENVELOPES_PER_SUBMIT = 10;

    /**
     * One-time prekeys per accepted upload batch. Every upload — initial
     * provisioning and replenishment alike — carries exactly this many keys,
     * so a device's pool is always established in full batches.
     */
    public static final int REPLENISH_BATCH_SIZE = 100;

    /** One-time recovery codes generated per set. */
    public static final int RECOVERY_CODES_PER_SET = 25;

    /** Recovery codes are consumed independently; positions are 0-based. */
    public static final int RECOVERY_CODE_POSITION_MIN = 0;

    public static final int RECOVERY_CODE_POSITION_MAX = 24;
}
