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

    /** One-time prekeys provisioned with a device's initial upload. */
    public static final int INITIAL_ONE_TIME_PREKEYS = 100;

    /** Replenish the pool when fewer than this many remain available. */
    public static final int REPLENISH_BELOW_AVAILABLE = 20;

    /** One-time prekeys added by each replenishment upload. */
    public static final int REPLENISH_BATCH_SIZE = 100;

    /** One-time recovery codes generated per set. */
    public static final int RECOVERY_CODES_PER_SET = 25;

    /** Recovery codes are consumed independently; positions are 0-based. */
    public static final int RECOVERY_CODE_POSITION_MIN = 0;

    public static final int RECOVERY_CODE_POSITION_MAX = 24;
}
