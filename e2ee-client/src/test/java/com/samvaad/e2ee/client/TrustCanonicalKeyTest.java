package com.samvaad.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.samvaad.e2ee.client.fake.FakeSignalAdapter;

/**
 * Trust is decided by byte-equality on the canonical identity public key
 * (ADR-0021 §10–§11). Display fingerprints are never persisted or compared:
 * these tests prove the trust primitive is the key bytes by holding the
 * fingerprint constant while keys change (must still pause) and varying the
 * fingerprint while keys stay identical (must stay trusted).
 */
class TrustCanonicalKeyTest {

    /**
     * Delegating adapter stub that pins the display fingerprint, decoupling
     * display from trust. (FakeSignalAdapter is final, so this wraps rather
     * than extends it.)
     */
    static final class PinnedFingerprintAdapter implements SignalAdapter {
        private final FakeSignalAdapter delegate;
        private final String pinned;

        PinnedFingerprintAdapter(FakeSignalAdapter delegate, String pinned) {
            this.delegate = delegate;
            this.pinned = pinned;
        }

        FakeSignalAdapter delegate() {
            return delegate;
        }

        @Override
        public LocalIdentity generateIdentity() {
            return delegate.generateIdentity();
        }

        @Override
        public SignedPrekeyPair generateSignedPrekey(SealedPrivateHandle identityPrivate, int prekeyId) {
            return delegate.generateSignedPrekey(identityPrivate, prekeyId);
        }

        @Override
        public OneTimePrekeyPair generateOneTimePrekey(int prekeyId) {
            return delegate.generateOneTimePrekey(prekeyId);
        }

        @Override
        public KyberPrekeyPair generateKyberPrekey(SealedPrivateHandle identityPrivate, int prekeyId) {
            return delegate.generateKyberPrekey(identityPrivate, prekeyId);
        }

        @Override
        public String fingerprint(byte[] identityPublicKey) {
            return pinned;
        }

        @Override
        public boolean verifySignedPrekey(byte[] identityPublicKey, byte[] signedPrekey, byte[] signature) {
            return delegate.verifySignedPrekey(identityPublicKey, signedPrekey, signature);
        }

        @Override
        public EstablishedSession establishOutbound(
                SealedPrivateHandle ownIdentityPrivate, CryptoTypes.RecipientBundle bundle) {
            return delegate.establishOutbound(ownIdentityPrivate, bundle);
        }

        @Override
        public DecryptResult decryptPrekeyInit(
                SealedPrivateHandle ownIdentityPrivate,
                SealedPrivateHandle ownSignedPrivate,
                OtpkResolver otpks,
                byte[] currentSessionBlobOrNull,
                byte[] envelopeCiphertext) {
            return delegate.decryptPrekeyInit(
                    ownIdentityPrivate, ownSignedPrivate, otpks, currentSessionBlobOrNull, envelopeCiphertext);
        }

        @Override
        public EncryptResult encrypt(byte[] sessionBlob, byte[] plaintextAssoc) {
            return delegate.encrypt(sessionBlob, plaintextAssoc);
        }

        @Override
        public DecryptResult decrypt(byte[] sessionBlob, byte[] envelopeCiphertext) {
            return delegate.decrypt(sessionBlob, envelopeCiphertext);
        }
    }

    /** Delegating stub whose fingerprint varies per call even for identical keys. */
    static final class VaryingFingerprintAdapter implements SignalAdapter {
        private final PinnedFingerprintAdapter pinned;
        private final AtomicInteger calls = new AtomicInteger();

        VaryingFingerprintAdapter(FakeSignalAdapter delegate) {
            this.pinned = new PinnedFingerprintAdapter(delegate, "unused");
        }

        FakeSignalAdapter delegate() {
            return pinned.delegate();
        }

        @Override
        public LocalIdentity generateIdentity() {
            return pinned.generateIdentity();
        }

        @Override
        public SignedPrekeyPair generateSignedPrekey(SealedPrivateHandle identityPrivate, int prekeyId) {
            return pinned.generateSignedPrekey(identityPrivate, prekeyId);
        }

        @Override
        public OneTimePrekeyPair generateOneTimePrekey(int prekeyId) {
            return pinned.generateOneTimePrekey(prekeyId);
        }

        @Override
        public KyberPrekeyPair generateKyberPrekey(SealedPrivateHandle identityPrivate, int prekeyId) {
            return pinned.generateKyberPrekey(identityPrivate, prekeyId);
        }

        @Override
        public String fingerprint(byte[] identityPublicKey) {
            return "fake-fp:varying:" + calls.incrementAndGet();
        }

        @Override
        public boolean verifySignedPrekey(byte[] identityPublicKey, byte[] signedPrekey, byte[] signature) {
            return pinned.verifySignedPrekey(identityPublicKey, signedPrekey, signature);
        }

        @Override
        public EstablishedSession establishOutbound(
                SealedPrivateHandle ownIdentityPrivate, CryptoTypes.RecipientBundle bundle) {
            return pinned.establishOutbound(ownIdentityPrivate, bundle);
        }

        @Override
        public DecryptResult decryptPrekeyInit(
                SealedPrivateHandle ownIdentityPrivate,
                SealedPrivateHandle ownSignedPrivate,
                OtpkResolver otpks,
                byte[] currentSessionBlobOrNull,
                byte[] envelopeCiphertext) {
            return pinned.decryptPrekeyInit(
                    ownIdentityPrivate, ownSignedPrivate, otpks, currentSessionBlobOrNull, envelopeCiphertext);
        }

        @Override
        public EncryptResult encrypt(byte[] sessionBlob, byte[] plaintextAssoc) {
            return pinned.encrypt(sessionBlob, plaintextAssoc);
        }

        @Override
        public DecryptResult decrypt(byte[] sessionBlob, byte[] envelopeCiphertext) {
            return pinned.decrypt(sessionBlob, envelopeCiphertext);
        }
    }

    @Test
    void identicalCanonicalKeysAreTrustedAsSameIdentity() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);

        byte[] first = CryptoTestFixtures.key("bob-phone:id");
        UUID msg1 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r1 = h.service().sendToDevices(msg1, sender,
                "hi".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());
        assertEquals(1, r1.sentCount());

        // Same key bytes in a fresh array instance: still the same identity.
        byte[] sameAgain = Arrays.copyOf(first, first.length);
        assertTrue(sameAgain != first);
        CryptoTypes.TrustRecord verdict = h.trust().observe(peer, sameAgain);
        assertEquals(CryptoTypes.TrustState.TRUSTED, verdict.state());
        assertArrayEquals(first, h.trust().load(peer).orElseThrow().identityPublicKey());

        UUID msg2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r2 = h.service().sendToDevices(msg2, sender,
                "hi2".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());
        assertEquals(1, r2.sentCount());
    }

    @Test
    void differentCanonicalKeysAreDetectedAsKeyChange() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);

        byte[] original = CryptoTestFixtures.key("bob-phone:id");
        UUID msg1 = UUID.randomUUID();
        h.service().sendToDevices(msg1, sender, "hi".getBytes(),
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());

        // One flipped bit in the same device's key: a key change.
        byte[] rotated = Arrays.copyOf(original, original.length);
        rotated[rotated.length - 1] ^= 0x01;
        CryptoTypes.TrustRecord verdict = h.trust().observe(peer, rotated);
        assertEquals(CryptoTypes.TrustState.PAUSED_KEY_CHANGED, verdict.state());
        // The paused record keeps the originally trusted bytes as the baseline.
        assertArrayEquals(original, h.trust().load(peer).orElseThrow().identityPublicKey());
    }

    @Test
    void keyChangePausesEvenWhenFingerprintsCollide() {
        UUID sender = UUID.randomUUID();
        FakeSignalAdapter fake = new FakeSignalAdapter();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender, fake);
        SamvaadCryptoService service = new SamvaadCryptoServiceImpl(
                new PinnedFingerprintAdapter(fake, "CONSTANT"),
                h.stores(), h.claimFake(), h.submitFake());
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);

        UUID msg1 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r1 = service.sendToDevices(msg1, sender,
                "hi".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());
        assertEquals(1, r1.sentCount());

        // Display collides by construction, yet the byte-different key pauses.
        h.claimFake().register(bob, peer, 1, "bob-phone-v2", 9);
        UUID msg2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r2 = service.sendToDevices(msg2, sender,
                "hi2".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone-v2", 9)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.PAUSED_KEY_CHANGED, r2.outcomes().get(peer));
    }

    @Test
    void identicalKeysStayTrustedEvenWhenFingerprintsVary() {
        UUID sender = UUID.randomUUID();
        FakeSignalAdapter fake = new FakeSignalAdapter();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender, fake);
        SamvaadCryptoService service = new SamvaadCryptoServiceImpl(
                new VaryingFingerprintAdapter(fake),
                h.stores(), h.claimFake(), h.submitFake());
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);

        List<CryptoTypes.RecipientBundle> directory =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9));
        UUID msg1 = UUID.randomUUID();
        assertEquals(1, service.sendToDevices(msg1, sender, "hi".getBytes(), directory, Set.of())
                .sentCount());
        UUID msg2 = UUID.randomUUID();
        assertEquals(1, service.sendToDevices(msg2, sender, "hi2".getBytes(), directory, Set.of())
                .sentCount());
        assertEquals(CryptoTypes.TrustState.TRUSTED, h.trust().load(peer).orElseThrow().state());
    }

    @Test
    void acceptKeyChangeStoresCanonicalBytesAndPrivateKeysStayOut() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);

        byte[] original = CryptoTestFixtures.key("bob-phone:id");
        UUID msg1 = UUID.randomUUID();
        h.service().sendToDevices(msg1, sender, "hi".getBytes(),
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());

        byte[] rotated = CryptoTestFixtures.key("bob-phone-v2:id");
        assertNotEquals(
                h.adapter().fingerprint(original), h.adapter().fingerprint(rotated));
        h.service().acceptKeyChange(peer, rotated);
        CryptoTypes.TrustRecord stored = h.trust().load(peer).orElseThrow();
        assertEquals(CryptoTypes.TrustState.TRUSTED, stored.state());
        assertArrayEquals(rotated, stored.identityPublicKey());

        // Trust record shape: canonical public bytes only — no fingerprint
        // string, no private handle anywhere in the contract.
        List<String> components = Arrays.stream(CryptoTypes.TrustRecord.class.getRecordComponents())
                .map(c -> c.getName() + ":" + c.getType().getSimpleName())
                .toList();
        assertEquals(List.of("peerDeviceId:UUID", "identityPublicKey:byte[]", "state:TrustState"),
                components);

        // Fingerprint display remains available as a derived representation.
        assertEquals(h.adapter().fingerprint(rotated),
                h.adapter().fingerprint(Arrays.copyOf(rotated, rotated.length)));
    }
}
