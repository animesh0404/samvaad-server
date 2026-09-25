package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.samvaad.samvaad_server.e2ee.auth.BoundActiveDeviceApprovalAuthorizer;
import com.samvaad.samvaad_server.e2ee.auth.DeviceApprovalAuthorizer;
import com.samvaad.samvaad_server.e2ee.crypto.KeyMaterialEnvelopeValidator;
import com.samvaad.samvaad_server.e2ee.crypto.TransportKeyMaterialValidator;
import com.samvaad.samvaad_server.e2ee.device.DeviceStatus;
import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.exception.DeviceApprovalDeniedException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidKeyMaterialException;
import com.samvaad.samvaad_server.user.User;

/**
 * Seam contracts without Spring: the envelope validator performs only honest
 * transport checks (never cryptographic verdicts), and the approval
 * authorizer trusts only a same-user ACTIVE-bound session.
 */
class E2eeSeamUnitTest {

    private final KeyMaterialEnvelopeValidator validator = new TransportKeyMaterialValidator();
    private final DeviceApprovalAuthorizer authorizer = new BoundActiveDeviceApprovalAuthorizer();

    @Test
    void envelopeValidatorRejectsMissingMaterial() {
        assertThrows(InvalidKeyMaterialException.class, () -> validator.validateDeviceIdentityKey(null));
        assertThrows(InvalidKeyMaterialException.class, () -> validator.validateSignedPrekey(new byte[0]));
        assertThrows(InvalidKeyMaterialException.class, () -> validator.validateSignedPrekeySignature(null));
        assertThrows(InvalidKeyMaterialException.class, () -> validator.validateOneTimePrekey(new byte[0]));
    }

    @Test
    void envelopeValidatorRejectsAbusiveSizesWithoutCryptoClaims() {
        byte[] oversized = new byte[TransportKeyMaterialValidator.MAX_KEY_BLOB_BYTES + 1];
        assertThrows(InvalidKeyMaterialException.class, () -> validator.validateDeviceIdentityKey(oversized));
        assertThrows(InvalidKeyMaterialException.class, () -> validator.validateSignedPrekey(oversized));
        assertThrows(InvalidKeyMaterialException.class, () -> validator.validateSignedPrekeySignature(oversized));
        assertThrows(InvalidKeyMaterialException.class, () -> validator.validateOneTimePrekey(oversized));
    }

    @Test
    void envelopeValidatorPassesOpaqueBytes() {
        assertDoesNotThrow(() -> validator.validateDeviceIdentityKey(new byte[]{1, 2, 3}));
        assertDoesNotThrow(() -> validator.validateSignedPrekey(new byte[]{1}));
        assertDoesNotThrow(() -> validator.validateSignedPrekeySignature(new byte[]{1}));
        assertDoesNotThrow(() -> validator.validateOneTimePrekey(new byte[]{1}));
    }

    @Test
    void approvalRequiresSameUserActiveBoundDevice() {
        User owner = new User(UUID.randomUUID());
        User other = new User(UUID.randomUUID());

        E2eeDevice active = device(owner, DeviceStatus.ACTIVE);
        E2eeDevice pending = device(owner, DeviceStatus.PENDING);
        assertDoesNotThrow(() -> authorizer.assertApprovalTrust(owner.getUserId(), active, pending));

        assertThrows(DeviceApprovalDeniedException.class,
                () -> authorizer.assertApprovalTrust(owner.getUserId(), null, pending));
        assertThrows(DeviceApprovalDeniedException.class,
                () -> authorizer.assertApprovalTrust(owner.getUserId(),
                        device(owner, DeviceStatus.PENDING), pending));
        assertThrows(DeviceApprovalDeniedException.class,
                () -> authorizer.assertApprovalTrust(owner.getUserId(),
                        device(owner, DeviceStatus.REVOKED), pending));

        E2eeDevice foreignActive = device(other, DeviceStatus.ACTIVE);
        assertThrows(DeviceApprovalDeniedException.class,
                () -> authorizer.assertApprovalTrust(other.getUserId(), foreignActive, pending));
        assertThrows(DeviceApprovalDeniedException.class,
                () -> authorizer.assertApprovalTrust(owner.getUserId(), active, device(owner, DeviceStatus.ACTIVE)));
        assertThrows(DeviceApprovalDeniedException.class,
                () -> authorizer.assertApprovalTrust(owner.getUserId(), active, device(owner, DeviceStatus.REVOKED)));
        assertThrows(DeviceApprovalDeniedException.class,
                () -> authorizer.assertApprovalTrust(owner.getUserId(), foreignActive, pending));
    }

    private E2eeDevice device(User owner, DeviceStatus status) {
        E2eeDevice device = new E2eeDevice();
        device.setUser(owner);
        device.setStatus(status);
        return device;
    }
}
