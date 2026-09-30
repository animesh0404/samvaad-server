package com.samvaad.samvaad_server.e2ee.device;

/**
 * Authority role of an enrolled E2EE device (ADR 0025). Exactly one
 * non-REVOKED PRIMARY per account owns durable conversation history;
 * COMPANION devices (at most four non-REVOKED) receive older history
 * through Primary-originated synchronization. The role is server-assigned
 * at enrollment and read-only over the API: clients never supply it.
 */
public enum DeviceRole {
    PRIMARY,
    COMPANION
}
