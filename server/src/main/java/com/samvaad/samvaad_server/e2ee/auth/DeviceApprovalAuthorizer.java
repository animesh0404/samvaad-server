package com.samvaad.samvaad_server.e2ee.auth;

import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;

import java.util.UUID;

/**
 * Authorization seam for trusted-device approval of a pending device (ADR
 * 0018 §6). The state machine ({@code PENDING → ACTIVE}) never changes;
 * only how "the caller is a trusted existing device" is proven.
 *
 * <p>The foundation implementation requires the caller's session to already
 * be bound to an ACTIVE device of the same user. The future identity-key/QR
 * proof replaces or strengthens this implementation behind the same port
 * without touching the enrollment state machine or the domain model.
 */
public interface DeviceApprovalAuthorizer {

    /**
     * Asserts the caller is trusted to approve {@code pendingDevice}.
     *
     * @param callerUserId        server-derived caller identity
     * @param callerBoundDevice   the device the caller's current session is
     *                            bound to, or {@code null} when the session
     *                            is not bound to any device
     * @param pendingDevice       the pending device awaiting approval
     * @throws com.samvaad.samvaad_server.e2ee.exception.DeviceApprovalDeniedException
     *                            when the caller is not trusted to approve
     */
    void assertApprovalTrust(UUID callerUserId, E2eeDevice callerBoundDevice, E2eeDevice pendingDevice);
}
