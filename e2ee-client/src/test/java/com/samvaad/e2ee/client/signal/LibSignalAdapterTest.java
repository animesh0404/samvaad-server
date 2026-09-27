package com.samvaad.e2ee.client.signal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.samvaad.e2ee.client.CryptoException;
import com.samvaad.e2ee.client.SignalAdapter;

/**
 * Unit coverage for the real libsignal adapter: canonical identity keys,
 * generation outputs, signature verification, and the ADR-0021 pair
 * fingerprint golden vectors. No fake adapter is involved.
 */
class LibSignalAdapterTest {

    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static byte[] key33(int fill) {
        byte[] key = new byte[33];
        key[0] = 0x05;
        Arrays.fill(key, 1, 33, (byte) fill);
        return key;
    }

    @Test
    void generatedIdentityIsCanonical33ByteKeyWithSealedPrivate() {
        LibSignalAdapter adapter = new LibSignalAdapter(1001);
        SignalAdapter.LocalIdentity identity = adapter.generateIdentity();

        assertNotNull(identity.identityPublicKey());
        assertEquals(33, identity.identityPublicKey().length);
        assertEquals(0x05, identity.identityPublicKey()[0]);
        assertNotNull(identity.identityPrivate());
        assertNotNull(identity.identityPrivate().handleId());

        // Two generations never collide.
        SignalAdapter.LocalIdentity other = adapter.generateIdentity();
        assertFalse(Arrays.equals(identity.identityPublicKey(), other.identityPublicKey()));
        assertNotEquals(identity.identityPrivate().handleId(), other.identityPrivate().handleId());

        // The contract carries no private bytes: LocalIdentity exposes only
        // public bytes plus an opaque handle.
        List<String> components = Arrays.stream(SignalAdapter.LocalIdentity.class.getRecordComponents())
                .map(c -> c.getName() + ":" + c.getType().getSimpleName())
                .toList();
        assertEquals(List.of("identityPublicKey:byte[]", "identityPrivate:SealedPrivateHandle"),
                components);
    }

    @Test
    void canonicalValidationRejectsNullLengthPrefixAndCurveGarbage() {
        LibSignalAdapter adapter = new LibSignalAdapter(1001);

        assertThrows(CryptoException.ClaimFailedException.class, () -> adapter.fingerprint(null));
        assertThrows(CryptoException.ClaimFailedException.class, () -> adapter.fingerprint(new byte[0]));
        assertThrows(CryptoException.ClaimFailedException.class,
                () -> adapter.fingerprint(new byte[32]));
        assertThrows(CryptoException.ClaimFailedException.class,
                () -> adapter.fingerprint(new byte[34]));
        byte[] badPrefix = key33(0x01);
        badPrefix[0] = 0x02;
        assertThrows(CryptoException.ClaimFailedException.class,
                () -> adapter.fingerprint(badPrefix));

        // Right shape, wrong curve material: rejected when it must enter
        // real key math (verification path fails closed rather than throwing).
        byte[] garbage = new byte[33];
        garbage[0] = 0x05;
        Arrays.fill(garbage, 1, 33, (byte) 0xFF);
        assertFalse(adapter.verifySignedPrekey(garbage, new byte[33], new byte[64]));
        assertFalse(adapter.verifySignedPrekey(null, new byte[33], new byte[64]));
        assertFalse(adapter.verifySignedPrekey(key33(0x01), null, new byte[64]));
        assertFalse(adapter.verifySignedPrekey(key33(0x01), new byte[33], null));
    }

    @Test
    void generatedSignedPrekeyVerifiesAndPreservesId() {
        LibSignalAdapter adapter = new LibSignalAdapter(1001);
        SignalAdapter.LocalIdentity identity = adapter.generateIdentity();
        SignalAdapter.SignedPrekeyPair signed = adapter.generateSignedPrekey(identity.identityPrivate(), 11);

        assertEquals(11, signed.prekeyId());
        assertEquals(33, signed.publicKey().length);
        assertNotNull(signed.signature());
        assertTrue(signed.signature().length > 0);
        assertTrue(adapter.verifySignedPrekey(
                identity.identityPublicKey(), signed.publicKey(), signed.signature()));

        // Wrong identity, tampered key, tampered signature all fail closed.
        SignalAdapter.LocalIdentity other = adapter.generateIdentity();
        assertFalse(adapter.verifySignedPrekey(
                other.identityPublicKey(), signed.publicKey(), signed.signature()));
        byte[] tamperedKey = Arrays.copyOf(signed.publicKey(), signed.publicKey().length);
        tamperedKey[5] ^= 0x01;
        assertFalse(adapter.verifySignedPrekey(
                identity.identityPublicKey(), tamperedKey, signed.signature()));
        byte[] tamperedSig = Arrays.copyOf(signed.signature(), signed.signature().length);
        tamperedSig[0] ^= 0x01;
        assertFalse(adapter.verifySignedPrekey(
                identity.identityPublicKey(), signed.publicKey(), tamperedSig));
    }

    @Test
    void generatedOneTimePrekeyPreservesId() {
        LibSignalAdapter adapter = new LibSignalAdapter(1001);
        SignalAdapter.OneTimePrekeyPair pair = adapter.generateOneTimePrekey(5001);
        assertEquals(5001, pair.prekeyId());
        assertEquals(33, pair.publicKey().length);
        assertNotNull(pair.privateHandle().handleId());
    }

    @Test
    void generatedKyberTripleHasValidShape() {
        LibSignalAdapter adapter = new LibSignalAdapter(1001);
        SignalAdapter.LocalIdentity identity = adapter.generateIdentity();
        SignalAdapter.KyberPrekeyPair kyber = adapter.generateKyberPrekey(identity.identityPrivate(), 22);

        assertEquals(22, kyber.prekeyId());
        // Kyber-1024 public key material (never interpreted here, only carried).
        assertEquals(1569, kyber.publicKey().length);
        assertNotNull(kyber.signature());
        assertTrue(kyber.signature().length > 0);
        assertNotNull(kyber.privateHandle().handleId());
    }

    @Test
    void singleKeyFingerprintIsStableDisplayNotVerificationString() {
        LibSignalAdapter adapter = new LibSignalAdapter(1001);
        SignalAdapter.LocalIdentity identity = adapter.generateIdentity();

        String first = adapter.fingerprint(identity.identityPublicKey());
        String second = adapter.fingerprint(
                Arrays.copyOf(identity.identityPublicKey(), identity.identityPublicKey().length));
        assertEquals(first, second);
        // Hex digest display: visually and structurally distinct from the
        // 60-digit grouped ADR-0021 verification string.
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void adr021GoldenVectorsMatchExactly() {
        assertEquals("00136 34446 03500 40354 18163 86955 22151 88162 40335 93379 98418 05716",
                IdentityFingerprints.displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02)));
        assertEquals("00530 85616 11504 63526 32365 68608 96307 52408 63097 80888 10308 98639",
                IdentityFingerprints.displayFingerprint(USER_A, key33(0x01), USER_C, key33(0x03)));
    }

    @Test
    void adr021PairFingerprintIsSymmetricAndKeySensitive() {
        String forward =
                IdentityFingerprints.displayFingerprint(USER_A, key33(0x01), USER_B, key33(0x02));
        String swapped =
                IdentityFingerprints.displayFingerprint(USER_B, key33(0x02), USER_A, key33(0x01));
        assertEquals(forward, swapped);

        byte[] rotated = key33(0x02);
        rotated[17] ^= 0x01;
        assertNotEquals(forward,
                IdentityFingerprints.displayFingerprint(USER_A, key33(0x01), USER_B, rotated));
        assertNotEquals(forward,
                IdentityFingerprints.displayFingerprint(USER_A, key33(0x01), USER_C, key33(0x02)));

        assertThrows(CryptoException.ClaimFailedException.class, () -> IdentityFingerprints
                .displayFingerprint(USER_A, null, USER_B, key33(0x02)));
        assertThrows(CryptoException.ClaimFailedException.class, () -> IdentityFingerprints
                .displayFingerprint(USER_A, new byte[10], USER_B, key33(0x02)));
    }

    @Test
    void establishmentRejectsBadBundlesDeterministically() {
        LibSignalAdapter adapter = new LibSignalAdapter(1001);
        SignalAdapter.LocalIdentity identity = adapter.generateIdentity();
        SignalAdapter.SignedPrekeyPair signed =
                adapter.generateSignedPrekey(identity.identityPrivate(), 11);
        SignalAdapter.KyberPrekeyPair kyber =
                adapter.generateKyberPrekey(identity.identityPrivate(), 22);
        UUID device = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        // Garbage identity key.
        assertThrows(CryptoException.ClaimFailedException.class, () -> adapter.establishOutbound(
                identity.identityPrivate(),
                bundle(device, user, new byte[33], signed.publicKey(), signed.signature(),
                        null, kyber)));

        // Missing Kyber triple: the library mandates it.
        com.samvaad.e2ee.client.CryptoTypes.RecipientBundle noKyber =
                new com.samvaad.e2ee.client.CryptoTypes.RecipientBundle(device, user, 1,
                        1001, identity.identityPublicKey(), 11, signed.publicKey(), signed.signature(),
                        null, null, null, null, null);
        assertThrows(CryptoException.ClaimFailedException.class,
                () -> adapter.establishOutbound(identity.identityPrivate(), noKyber));

        // Corrupt signed-prekey signature and corrupt Kyber signature.
        byte[] badSig = Arrays.copyOf(signed.signature(), signed.signature().length);
        badSig[0] ^= 0x01;
        assertThrows(CryptoException.ClaimFailedException.class, () -> adapter.establishOutbound(
                identity.identityPrivate(),
                bundle(device, user, identity.identityPublicKey(), signed.publicKey(), badSig,
                        null, kyber)));

        byte[] badKyberSig = Arrays.copyOf(kyber.signature(), kyber.signature().length);
        badKyberSig[0] ^= 0x01;
        SignalAdapter.KyberPrekeyPair badKyber = new SignalAdapter.KyberPrekeyPair(
                kyber.prekeyId(), kyber.publicKey(), badKyberSig, kyber.privateHandle());
        assertThrows(CryptoException.ClaimFailedException.class, () -> adapter.establishOutbound(
                identity.identityPrivate(),
                bundle(device, user, identity.identityPublicKey(), signed.publicKey(),
                        signed.signature(), null, badKyber)));

        // Malformed envelope bytes are corruption, not claims.
        assertThrows(CryptoException.SessionCorruptException.class,
                () -> adapter.decrypt(new byte[]{1, 2, 3}, new byte[]{9}));
        assertThrows(CryptoException.SessionCorruptException.class,
                () -> adapter.decryptPrekeyInit(identity.identityPrivate(),
                        adapter.generateSignedPrekey(identity.identityPrivate(), 11).privateHandle(),
                        id -> {
                            throw new CryptoException.ClaimFailedException("unused");
                        }, null, new byte[]{9}));
    }

    /** Bundle builder over real adapter outputs (null OTPK pair = fallback). */
    static com.samvaad.e2ee.client.CryptoTypes.RecipientBundle bundle(
            UUID device, UUID user, byte[] identity, byte[] spk, byte[] spkSig,
            SignalAdapter.OneTimePrekeyPair otp,
            SignalAdapter.KyberPrekeyPair kyber) {
        return new com.samvaad.e2ee.client.CryptoTypes.RecipientBundle(device, user, 1,
                1002, identity, 11, spk, spkSig,
                otp == null ? null : otp.prekeyId(), otp == null ? null : otp.publicKey(),
                kyber.prekeyId(), kyber.publicKey(), kyber.signature());
    }
}
