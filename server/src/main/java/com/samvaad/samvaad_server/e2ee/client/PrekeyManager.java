package com.samvaad.samvaad_server.e2ee.client;

import java.util.List;

/**
 * Own-device one-time-prekey lifecycle.
 *
 * <p>Locked policy mirrored here: batch size exactly 100
 * ({@code E2eePolicy.REPLENISH_BATCH_SIZE}), replenish when available &lt; 20.
 * This interface owns ID allocation so IDs are never reused for this device.
 *
 * <p>Inputs: server-reported available count. Outputs: replenishment decision
 * and next upload batch (public parts only; private handles stay sealed in
 * {@link DeviceKeyStore}).
 *
 * <p>Ownership: owns the next-prekey-ID counter and the pending-batch record.
 *
 * <p>Persistence: the allocated-ID high-water mark must be durable so a
 * restart never re-issues an ID.
 *
 * <p>Security invariants: batch size is fixed; IDs strictly increase.
 */
public interface PrekeyManager {

    /** Fixed V1 batch size. */
    int BATCH_SIZE = 100;

    /** Replenish threshold. */
    int REPLENISH_THRESHOLD = 20;

    /** True when the server-reported available count needs replenishment. */
    boolean needsReplenishment(long availableCount);

    /** Public batch for upload; registers private handles in the device store. */
    List<SignalAdapter.OneTimePrekeyPair> nextBatch();

    /** Highest prekey ID issued so far (durable high-water mark). */
    int highWaterMark();
}
