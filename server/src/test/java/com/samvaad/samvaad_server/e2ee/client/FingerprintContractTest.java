package com.samvaad.samvaad_server.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Executable spec for ADR-0021 (JDK primitives only — no crypto library).
 * The private reference implementation below mirrors the ADR construction
 * exactly; every platform adapter (JVM, Android, Web/Tauri) must reproduce
 * the golden vectors byte-for-byte. Fixture keys are byte patterns, not
 * curve points: vectors pin the construction, curve validity remains the
 * crypto layer's job.
 */
class FingerprintContractTest {

    private static final byte[] DOMAIN = "SAMVAAD-FP-V1".getBytes(StandardCharsets.US_ASCII);

    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String V1_DISPLAY =
            "00136 34446 03500 40354 18163 86955 22151 88162 40335 93379 98418 05716";
    private static final String V3_DISPLAY =
            "00530 85616 11504 63526 32365 68608 96307 52408 63097 80888 10308 98639";

    private static byte[] key33(int fill) {
        byte[] key = new byte[33];
        key[0] = 0x05;
        Arrays.fill(key, 1, 33, (byte) fill);
        return key;
    }

    private static byte[] uuid16(UUID id) {
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (id.getMostSignificantBits() >>> (56 - 8 * i));
            out[8 + i] = (byte) (id.getLeastSignificantBits() >>> (56 - 8 * i));
        }
        return out;
    }

    /** Reference implementation of the ADR-0021 display construction. */
    static String displayFingerprint(UUID localUser, byte[] localKey, UUID remoteUser, byte[] remoteKey) {
        return displayWithDomain(DOMAIN, localUser, localKey, remoteUser, remoteKey);
    }

    static String displayWithDomain(
            byte[] domain, UUID localUser, byte[] localKey, UUID remoteUser, byte[] remoteKey) {
        byte[] a = concat(uuid16(localUser), localKey);
        byte[] b = concat(uuid16(remoteUser), remoteKey);
        byte[] first;
        byte[] second;
        if (compareUnsigned(a, b) <= 0) {
            first = a;
            second = b;
        } else {
            first = b;
            second = a;
        }
        byte[] digest = sha256(concat(domain, concat(first, second)));
        byte[] head = Arrays.copyOf(digest, 24);
        java.math.BigInteger value = new java.math.BigInteger(1, head);
        String digits = value.toString();
        assertTrue(digits.length() <= 60);
        while (digits.length() < 60) {
            digits = "0" + digits;
        }
        StringBuilder grouped = new StringBuilder(71);
        for (int i = 0; i < 60; i += 5) {
            if (i > 0) {
                grouped.append(' ');
            }
            grouped.append(digits, i, i + 5);
        }
        return grouped.toString();
    }

    private static byte[] concat(byte[] x, byte[] y) {
        byte[] out = Arrays.copyOf(x, x.length + y.length);
        System.arraycopy(y, 0, out, x.length, y.length);
        return out;
    }

    private static int compareUnsigned(byte[] x, byte[] y) {
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int diff = (x[i] & 0xFF) - (y[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return x.length - y.length;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void canonicalIdentityEncodingIsVersioned33Bytes() {
        byte[] key = key33(0x01);
        assertEquals(33, key.length);
        assertEquals(0x05, key[0]);
    }

    @Test
    void uuidEncodingIsBigEndianMsbFirst() {
        byte[] encoded = uuid16(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertEquals(16, encoded.length);
        for (int i = 0; i < 16; i++) {
            assertEquals(0x11, encoded[i]);
        }
    }

    @Test
    void goldenVectorV1() {
        assertEquals(V1_DISPLAY, displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02)));
    }

    @Test
    void goldenVectorV3() {
        assertEquals(V3_DISPLAY, displayFingerprint(USER_A, key33(0x01), USER_C, key33(0x03)));
    }

    @Test
    void viewingOrderDoesNotMatter() {
        assertEquals(displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02)),
                displayFingerprint(USER_B, key33(0x02), USER_A, key33(0x01)));
    }

    @Test
    void displayIsDeterministicAndFormatted() {
        String first = displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02));
        String second = displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02));
        assertEquals(first, second);
        assertEquals(71, first.length());
        assertTrue(first.replace(" ", "").matches("\\d{60}"));
    }

    @Test
    void keyChangeRuleIsByteEquality() {
        // Identical bytes: no change.
        assertEquals(displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02)),
                displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02)));
        // One flipped bit in the same device's key: a key change.
        byte[] rotated = key33(0x02);
        rotated[17] ^= 0x01;
        assertNotEquals(displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02)),
                displayFingerprint(USER_A, key33(0x01), USER_B, rotated));
        // Same key under a different user: a different pair, not a change.
        assertNotEquals(displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02)),
                displayFingerprint(USER_A, key33(0x01), USER_C, key33(0x02)));
    }

    @Test
    void domainSeparationIsLoadBearing() {
        byte[] other = "SAMVAAD-FP-V1X".getBytes(StandardCharsets.US_ASCII);
        assertNotEquals(displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02)),
                displayWithDomain(other, USER_A, key33(0x01), USER_B, key33(0x02)));
    }
}
