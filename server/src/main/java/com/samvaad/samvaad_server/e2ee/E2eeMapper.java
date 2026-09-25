package com.samvaad.samvaad_server.e2ee;

import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekey;
import com.samvaad.samvaad_server.e2ee.dto.ClaimPrekeyResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.OneTimePrekeyDto;
import com.samvaad.samvaad_server.e2ee.dto.RecipientDeviceDto;
import com.samvaad.samvaad_server.e2ee.exception.InvalidKeyMaterialException;

import java.util.Base64;

/**
 * Mapping between E2EE persistence entities and API DTOs. Key material
 * crosses the wire as standard Base64 of opaque public bytes. Decoding
 * failures are validation errors, not cryptographic verdicts: the server
 * makes no claim about Signal-level validity of the decoded bytes.
 */
public final class E2eeMapper {

    private static final Base64.Decoder DECODER = Base64.getDecoder();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();

    private E2eeMapper() {
    }

    public static byte[] decodeBase64(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidKeyMaterialException(field + " must be present and non-empty");
        }
        try {
            return DECODER.decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidKeyMaterialException(field + " is not valid Base64");
        }
    }

    public static String encodeBase64(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    public static DeviceDto toDeviceDto(E2eeDevice device, long availablePrekeys) {
        DeviceDto dto = new DeviceDto();
        dto.setDeviceId(device.getDeviceId());
        dto.setRegistrationId(device.getRegistrationId());
        dto.setDeviceIdentityPublicKey(encodeBase64(device.getDeviceIdentityPublicKey()));
        dto.setSignedPrekeyId(device.getSignedPrekeyId());
        dto.setStatus(device.getStatus());
        dto.setClientPlatform(device.getClientPlatform());
        dto.setClientName(device.getClientName());
        dto.setClientVersion(device.getClientVersion());
        dto.setAvailablePrekeys(availablePrekeys);
        dto.setCreatedAt(device.getCreatedAt());
        dto.setLastActiveAt(device.getLastActiveAt());
        return dto;
    }

    public static RecipientDeviceDto toRecipientDeviceDto(E2eeDevice device, boolean hasAvailableOneTimePrekey) {
        RecipientDeviceDto dto = new RecipientDeviceDto();
        dto.setDeviceId(device.getDeviceId());
        dto.setRegistrationId(device.getRegistrationId());
        dto.setDeviceIdentityPublicKey(encodeBase64(device.getDeviceIdentityPublicKey()));
        dto.setSignedPrekeyId(device.getSignedPrekeyId());
        dto.setSignedPrekey(encodeBase64(device.getSignedPrekey()));
        dto.setSignedPrekeySignature(encodeBase64(device.getSignedPrekeySignature()));
        dto.setHasAvailableOneTimePrekey(hasAvailableOneTimePrekey);
        return dto;
    }

    public static ClaimPrekeyResponseDto toClaimResponseDto(E2eeDevice device, E2eeOneTimePrekey consumed) {
        ClaimPrekeyResponseDto dto = new ClaimPrekeyResponseDto();
        dto.setDeviceId(device.getDeviceId());
        dto.setRegistrationId(device.getRegistrationId());
        dto.setDeviceIdentityPublicKey(encodeBase64(device.getDeviceIdentityPublicKey()));
        dto.setSignedPrekeyId(device.getSignedPrekeyId());
        dto.setSignedPrekey(encodeBase64(device.getSignedPrekey()));
        dto.setSignedPrekeySignature(encodeBase64(device.getSignedPrekeySignature()));
        if (consumed != null) {
            dto.setOneTimePrekey(new OneTimePrekeyDto(
                    consumed.getPrekeyId(), encodeBase64(consumed.getPublicKey())));
        }
        return dto;
    }
}
