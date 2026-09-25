package com.samvaad.samvaad_server.e2ee.auth;

import com.samvaad.samvaad_server.e2ee.device.DeviceStatus;
import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.exception.DeviceApprovalDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Foundation approval trust: the caller's session must already be bound to
 * an ACTIVE device of the same user, and the pending device must belong to
 * that user and still be PENDING. Ordinary password-only sessions are never
 * trusted to approve; owner authorization alone does not bind a session to
 * a device. This implementation is intentionally non-cryptographic: the
 * identity-key/QR proof is a future replacement behind the same port.
 */
@Component
public class BoundActiveDeviceApprovalAuthorizer implements DeviceApprovalAuthorizer {

    @Override
    public void assertApprovalTrust(UUID callerUserId, E2eeDevice callerBoundDevice, E2eeDevice pendingDevice) {
        if (callerBoundDevice == null
                || callerBoundDevice.getStatus() != DeviceStatus.ACTIVE
                || callerBoundDevice.getUser() == null
                || !callerUserId.equals(callerBoundDevice.getUser().getUserId())) {
            throw new DeviceApprovalDeniedException();
        }
        if (pendingDevice == null
                || pendingDevice.getStatus() != DeviceStatus.PENDING
                || pendingDevice.getUser() == null
                || !callerUserId.equals(pendingDevice.getUser().getUserId())) {
            throw new DeviceApprovalDeniedException();
        }
    }
}
