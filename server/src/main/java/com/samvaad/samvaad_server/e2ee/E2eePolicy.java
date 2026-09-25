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
