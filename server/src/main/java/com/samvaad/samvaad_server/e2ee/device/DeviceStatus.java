package com.samvaad.samvaad_server.e2ee.device;

/**
 * Lifecycle of an independent cryptographic E2EE device. Transitions are
 * one-directional: PENDING becomes ACTIVE only through an enrollment path,
 * and REVOKED is terminal. A revoked identity is never resurrected;
 * re-enrollment always creates a new device record.
 */
public enum DeviceStatus {
    PENDING,
    ACTIVE,
    REVOKED
}
