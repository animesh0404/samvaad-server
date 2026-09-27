package com.samvaad.e2ee.client.signal;

import com.samvaad.e2ee.client.CryptoException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact JVM implementation of the ADR-0021 V1 identity fingerprint
 * construction (JDK primitives only, no crypto-library calls).
 *
 * <p> fingerprinted material: each party's immutable Samvaad {@code userId}
 * (16 bytes big-endian) plus that device's 33-byte canonical identity
 * public key ({@code 0x05 || X25519}); entries sorted in ascending
 * lexicographic unsigned byte order; SHA-256 over {@code "SAMVAAD-FP-V1" ||
 * A || B}; first 24 digest bytes as a big-endian unsigned integer,
 * zero-padded to 60 decimal digits, grouped twelve-by-five with single
 * spaces.
 *
 * <p>This string is a derived human-verification display only. Trust
 * decisions compare canonical key bytes, never this string; it must never
 * be persisted as authoritative trust state.
 */
public final class IdentityFingerprints {

    private static final byte[] DOMAIN = "SAMVAAD-FP-V1".getBytes(StandardCharsets.US_ASCII);

    private IdentityFingerprints() {
    }

    /**
     * Canonical 33-byte identity-key gate shared by the adapter and this
     * construction: null, wrong length, wrong version byte, or
     * non-curve material is rejected, never reinterpreted.
     */
    public static byte[] requireCanonicalKey(byte[] identityPublicKey) {
        if (identityPublicKey == null) {
            throw new CryptoException.ClaimFailedException("null identity key");
        }
        if (identityPublicKey.length != 33) {
            throw new CryptoException.ClaimFailedException(
                    "identity key must be 33 bytes, was " + identityPublicKey.length);
        }
        if (identityPublicKey[0] != 0x05) {
            throw new CryptoException.ClaimFailedException(
                    "identity key must start with version byte 0x05");
        }
        return identityPublicKey;
    }

    /**
     * ADR-0021 display string for the pair. Viewing order does not matter:
     * both sides compute identical output.
     */
    public static String displayFingerprint(
            UUID localUser, byte[] localKey, UUID remoteUser, byte[] remoteKey) {
        Objects.requireNonNull(localUser, "localUser");
        Objects.requireNonNull(remoteUser, "remoteUser");
        requireCanonicalKey(localKey);
        requireCanonicalKey(remoteKey);
        byte[] a = concat(uuid16(localUser), Arrays.copyOf(localKey, localKey.length));
        byte[] b = concat(uuid16(remoteUser), Arrays.copyOf(remoteKey, remoteKey.length));
        byte[] first;
        byte[] second;
        if (compareUnsigned(a, b) <= 0) {
            first = a;
            second = b;
        } else {
            first = b;
            second = a;
        }
        byte[] digest = sha256(concat(DOMAIN, concat(first, second)));
        byte[] head = Arrays.copyOf(digest, 24);
        String digits = new BigInteger(1, head).toString();
        while (digits.length() < 60) {
            digits = "0" + digits;
        }
        if (digits.length() > 60) {
            throw new IllegalStateException("fingerprint digest out of range");
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

    private static byte[] uuid16(UUID id) {
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (id.getMostSignificantBits() >>> (56 - 8 * i));
            out[8 + i] = (byte) (id.getLeastSignificantBits() >>> (56 - 8 * i));
        }
        return out;
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
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
