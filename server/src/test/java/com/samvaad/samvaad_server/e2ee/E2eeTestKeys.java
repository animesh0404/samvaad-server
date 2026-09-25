package com.samvaad.samvaad_server.e2ee;

import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceRequestDto;
import com.samvaad.samvaad_server.e2ee.dto.OneTimePrekeyDto;
import com.samvaad.samvaad_server.e2ee.dto.UploadOneTimePrekeysDto;
import com.samvaad.samvaad_server.session.ClientPlatform;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Deterministic public-key fixtures for E2EE tests. Bytes are opaque and
 * distinct per seed; they assert envelope handling only, never cryptographic
 * validity.
 */
public final class E2eeTestKeys {

    private E2eeTestKeys() {
    }

    public static String key(int seed) {
        return key(seed, 48);
    }

    public static String key(int seed, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((seed * 31 + i * 17 + 7) & 0xFF);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static EnrollDeviceRequestDto enrollRequest(int seed, ClientPlatform platform) {
        EnrollDeviceRequestDto request = new EnrollDeviceRequestDto();
        request.setRegistrationId(1000 + seed);
        request.setDeviceIdentityPublicKey(key(seed * 10 + 1));
        request.setSignedPrekeyId(2000 + seed);
        request.setSignedPrekey(key(seed * 10 + 2));
        request.setSignedPrekeySignature(key(seed * 10 + 3, 64));
        request.setClientPlatform(platform);
        request.setClientName("Test Client");
        request.setClientVersion("1.0.0");
        return request;
    }

    public static UploadOneTimePrekeysDto uploadBatch(int firstPrekeyId) {
        List<OneTimePrekeyDto> prekeys = new ArrayList<>(E2eePolicy.REPLENISH_BATCH_SIZE);
        for (int i = 0; i < E2eePolicy.REPLENISH_BATCH_SIZE; i++) {
            prekeys.add(new OneTimePrekeyDto(firstPrekeyId + i, key(5000 + firstPrekeyId + i)));
        }
        return new UploadOneTimePrekeysDto(prekeys);
    }

    public static UploadOneTimePrekeysDto uploadBatch(int firstPrekeyId, int size) {
        List<OneTimePrekeyDto> prekeys = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            prekeys.add(new OneTimePrekeyDto(firstPrekeyId + i, key(9000 + firstPrekeyId + i)));
        }
        return new UploadOneTimePrekeysDto(prekeys);
    }
}
