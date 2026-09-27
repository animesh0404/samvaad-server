package com.samvaad.samvaad_server.e2ee.client.signal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.samvaad.samvaad_server.e2ee.client.CryptoException;
import com.samvaad.samvaad_server.e2ee.client.CryptoTypes;
import com.samvaad.samvaad_server.e2ee.client.SignalAdapter;

/**
 * Real two-device JVM interoperability proof with libsignal on both sides
 * (no fake adapter): first message, reply, multi-message ratcheting,
 * exactly-once OTPK, signed-prekey fallback, simultaneous initiation,
 * and session-blob reload. Devices use distinct registration ids.
 */
class SignalInteropTest {

    /** One real device: adapter plus its generated key material. */
    static final class Device {
        final UUID userId = UUID.randomUUID();
        final UUID deviceId = UUID.randomUUID();
        final int signalDeviceId;
        final int registrationId;
        final LibSignalAdapter adapter;
        final SignalAdapter.LocalIdentity identity;
        final SignalAdapter.SignedPrekeyPair signed;
        final SignalAdapter.KyberPrekeyPair kyber;
        final List<SignalAdapter.OneTimePrekeyPair> otpks = new ArrayList<>();
        /** Samvaad-side OTPK private custody: id -> sealed handle. */
        final Map<Integer, SignalAdapter.SealedPrivateHandle> otpkPrivates = new HashMap<>();

        Device(int signalDeviceId, int registrationId) {
            this.signalDeviceId = signalDeviceId;
            this.registrationId = registrationId;
            this.adapter = new LibSignalAdapter(registrationId);
            this.identity = adapter.generateIdentity();
            this.signed = adapter.generateSignedPrekey(identity.identityPrivate(), 11);
            this.kyber = adapter.generateKyberPrekey(identity.identityPrivate(), 22);
        }

        void issueOtpks(int firstId, int count) {
            for (int i = 0; i < count; i++) {
                SignalAdapter.OneTimePrekeyPair pair =
                        adapter.generateOneTimePrekey(firstId + i);
                otpks.add(pair);
                otpkPrivates.put(pair.prekeyId(), pair.privateHandle());
            }
        }

        /** Public bundle for this device, optionally without OTPK (fallback). */
        CryptoTypes.RecipientBundle bundle(boolean withOtpk) {
            SignalAdapter.OneTimePrekeyPair otp = withOtpk && !otpks.isEmpty() ? otpks.get(0) : null;
            return new CryptoTypes.RecipientBundle(deviceId, userId, signalDeviceId, registrationId,
                    identity.identityPublicKey(), signed.prekeyId(), signed.publicKey(),
                    signed.signature(),
                    otp == null ? null : otp.prekeyId(), otp == null ? null : otp.publicKey(),
                    kyber.prekeyId(), kyber.publicKey(), kyber.signature());
        }

        /** Samvaad-side resolver: peek exactly the referenced handle. */
        SignalAdapter.OtpkResolver resolver() {
            return id -> {
                SignalAdapter.SealedPrivateHandle handle = otpkPrivates.get(id);
                if (handle == null) {
                    throw new CryptoException.ClaimFailedException(
                            "unknown or already-consumed one-time prekey: " + id);
                }
                return handle;
            };
        }

        void consume(int id) {
            otpkPrivates.remove(id);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void firstMessageReplyAndRatchetBothDirections() {
        Device alice = new Device(1, 1001);
        Device bob = new Device(1, 1002);
        bob.issueOtpks(100, 3);

        // Alice establishes with Bob's bundle (OTPK present) and encrypts.
        SignalAdapter.EstablishedSession established =
                alice.adapter.establishOutbound(alice.identity.identityPrivate(), bob.bundle(true));
        assertEquals(CryptoTypes.EstablishmentMode.WITH_ONE_TIME_PREKEY, established.mode());
        SignalAdapter.EncryptResult first =
                alice.adapter.encrypt(established.sessionBlob(), bytes("hello-bob"));

        // Bob processes PREKEY_INIT: exact OTPK resolved, session converges.
        SignalAdapter.DecryptResult inbound = bob.adapter.decryptPrekeyInit(
                bob.identity.identityPrivate(), bob.signed.privateHandle(), bob.resolver(),
                null, first.envelopeCiphertext());
        assertArrayEquals(bytes("hello-bob"), inbound.plaintextAssoc());
        assertNotNull(inbound.consumedOneTimePrekeyIdOrNull());
        assertEquals(100, inbound.consumedOneTimePrekeyIdOrNull());
        bob.consume(inbound.consumedOneTimePrekeyIdOrNull());

        // Reply on the converged session; Alice ratchet-decrypts.
        SignalAdapter.EncryptResult reply =
                bob.adapter.encrypt(inbound.updatedSessionBlob(), bytes("hi-alice"));
        SignalAdapter.DecryptResult back = alice.adapter.decrypt(
                first.updatedSessionBlob(), reply.envelopeCiphertext());
        assertArrayEquals(bytes("hi-alice"), back.plaintextAssoc());
        assertNull(back.consumedOneTimePrekeyIdOrNull());

        // Multiple messages both directions: state advances, bytes differ.
        byte[] aliceBlob = back.updatedSessionBlob();
        byte[] bobBlob = reply.updatedSessionBlob();
        List<byte[]> seen = new ArrayList<>();
        seen.add(first.envelopeCiphertext());
        seen.add(reply.envelopeCiphertext());
        for (int i = 0; i < 4; i++) {
            SignalAdapter.EncryptResult out;
            SignalAdapter.DecryptResult in;
            if (i % 2 == 0) {
                out = alice.adapter.encrypt(aliceBlob, bytes("a" + i));
                in = bob.adapter.decrypt(bobBlob, out.envelopeCiphertext());
                assertArrayEquals(bytes("a" + i), in.plaintextAssoc());
            } else {
                out = bob.adapter.encrypt(bobBlob, bytes("b" + i));
                in = alice.adapter.decrypt(aliceBlob, out.envelopeCiphertext());
                assertArrayEquals(bytes("b" + i), in.plaintextAssoc());
            }
            for (byte[] prior : seen) {
                assertNotEquals(new String(prior, StandardCharsets.UTF_8),
                        new String(out.envelopeCiphertext(), StandardCharsets.UTF_8));
            }
            seen.add(out.envelopeCiphertext());
            if (i % 2 == 0) {
                bobBlob = in.updatedSessionBlob();
                aliceBlob = out.updatedSessionBlob();
            } else {
                aliceBlob = in.updatedSessionBlob();
                bobBlob = out.updatedSessionBlob();
            }
        }
    }

    @Test
    void otpkConsumedExactlyOnceAndReplayFailsClosed() {
        Device alice = new Device(1, 1001);
        Device bob = new Device(1, 1002);
        bob.issueOtpks(100, 2);

        SignalAdapter.EstablishedSession established =
                alice.adapter.establishOutbound(alice.identity.identityPrivate(), bob.bundle(true));
        SignalAdapter.EncryptResult first =
                alice.adapter.encrypt(established.sessionBlob(), bytes("once"));

        SignalAdapter.DecryptResult inbound = bob.adapter.decryptPrekeyInit(
                bob.identity.identityPrivate(), bob.signed.privateHandle(), bob.resolver(),
                null, first.envelopeCiphertext());
        assertEquals(100, inbound.consumedOneTimePrekeyIdOrNull());
        bob.consume(100);

        // Replay of the same envelope: deterministic rejection, other OTPK intact.
        CryptoException.ClaimFailedException firstFailure = assertThrows(
                CryptoException.ClaimFailedException.class, () -> bob.adapter.decryptPrekeyInit(
                        bob.identity.identityPrivate(), bob.signed.privateHandle(), bob.resolver(),
                        inbound.updatedSessionBlob(), first.envelopeCiphertext()));
        CryptoException.ClaimFailedException secondFailure = assertThrows(
                CryptoException.ClaimFailedException.class, () -> bob.adapter.decryptPrekeyInit(
                        bob.identity.identityPrivate(), bob.signed.privateHandle(), bob.resolver(),
                        inbound.updatedSessionBlob(), first.envelopeCiphertext()));
        assertEquals(firstFailure.getMessage(), secondFailure.getMessage());
        assertTrue(bob.otpkPrivates.containsKey(101));

        // Bob's reply advances both sides past the prekey phase: Alice's next
        // message is a true ratchet message that decrypts without touching
        // the remaining OTPK.
        SignalAdapter.EncryptResult reply =
                bob.adapter.encrypt(inbound.updatedSessionBlob(), bytes("reply"));
        SignalAdapter.DecryptResult replyBack = alice.adapter.decrypt(
                first.updatedSessionBlob(), reply.envelopeCiphertext());
        assertArrayEquals(bytes("reply"), replyBack.plaintextAssoc());

        SignalAdapter.EncryptResult second =
                alice.adapter.encrypt(replyBack.updatedSessionBlob(), bytes("twice"));
        assertEquals(CryptoTypes.EnvelopeType.RATCHET, second.envelopeType());
        SignalAdapter.DecryptResult viaRatchet = bob.adapter.decrypt(
                reply.updatedSessionBlob(), second.envelopeCiphertext());
        assertArrayEquals(bytes("twice"), viaRatchet.plaintextAssoc());
        assertTrue(bob.otpkPrivates.containsKey(101));
    }

    @Test
    void signedPrekeyFallbackWithoutOtpk() {
        Device alice = new Device(1, 1001);
        Device bob = new Device(1, 1002);

        SignalAdapter.EstablishedSession established =
                alice.adapter.establishOutbound(alice.identity.identityPrivate(), bob.bundle(false));
        assertEquals(CryptoTypes.EstablishmentMode.SIGNED_PREKEY_FALLBACK, established.mode());
        SignalAdapter.EncryptResult first =
                alice.adapter.encrypt(established.sessionBlob(), bytes("fallback-hi"));

        SignalAdapter.DecryptResult inbound = bob.adapter.decryptPrekeyInit(
                bob.identity.identityPrivate(), bob.signed.privateHandle(), bob.resolver(),
                null, first.envelopeCiphertext());
        assertArrayEquals(bytes("fallback-hi"), inbound.plaintextAssoc());
        assertNull(inbound.consumedOneTimePrekeyIdOrNull());

        SignalAdapter.EncryptResult reply =
                bob.adapter.encrypt(inbound.updatedSessionBlob(), bytes("fallback-ho"));
        SignalAdapter.DecryptResult back = alice.adapter.decrypt(
                first.updatedSessionBlob(), reply.envelopeCiphertext());
        assertArrayEquals(bytes("fallback-ho"), back.plaintextAssoc());
    }

    @Test
    void simultaneousInitiationConvergesBothDirections() {
        Device alice = new Device(1, 1001);
        Device bob = new Device(1, 1002);
        alice.issueOtpks(500, 2);
        bob.issueOtpks(100, 2);

        // Both sides establish at once (Sesame case), then cross-decrypt.
        SignalAdapter.EstablishedSession aliceOut = alice.adapter.establishOutbound(
                alice.identity.identityPrivate(), bob.bundle(true));
        SignalAdapter.EstablishedSession bobOut = bob.adapter.establishOutbound(
                bob.identity.identityPrivate(), aliceBundle(alice));
        SignalAdapter.EncryptResult fromAlice =
                alice.adapter.encrypt(aliceOut.sessionBlob(), bytes("from-a"));
        SignalAdapter.EncryptResult fromBob =
                bob.adapter.encrypt(bobOut.sessionBlob(), bytes("from-b"));

        SignalAdapter.DecryptResult bobIn = bob.adapter.decryptPrekeyInit(
                bob.identity.identityPrivate(), bob.signed.privateHandle(), bob.resolver(),
                bobOut.sessionBlob(), fromAlice.envelopeCiphertext());
        assertArrayEquals(bytes("from-a"), bobIn.plaintextAssoc());
        SignalAdapter.DecryptResult aliceIn = alice.adapter.decryptPrekeyInit(
                alice.identity.identityPrivate(), alice.signed.privateHandle(), alice.resolver(),
                aliceOut.sessionBlob(), fromBob.envelopeCiphertext());
        assertArrayEquals(bytes("from-b"), aliceIn.plaintextAssoc());

        // Neither side lost its session: follow-ups flow both ways.
        SignalAdapter.EncryptResult a2 =
                alice.adapter.encrypt(aliceIn.updatedSessionBlob(), bytes("a2"));
        SignalAdapter.DecryptResult b2 =
                bob.adapter.decrypt(bobIn.updatedSessionBlob(), a2.envelopeCiphertext());
        assertArrayEquals(bytes("a2"), b2.plaintextAssoc());
    }

    @Test
    void reloadedSessionBlobsRemainUsable() {
        Device alice = new Device(1, 1001);
        Device bob = new Device(1, 1002);
        bob.issueOtpks(100, 1);

        SignalAdapter.EstablishedSession established =
                alice.adapter.establishOutbound(alice.identity.identityPrivate(), bob.bundle(true));
        SignalAdapter.EncryptResult first =
                alice.adapter.encrypt(established.sessionBlob(), bytes("persist-me"));
        SignalAdapter.DecryptResult inbound = bob.adapter.decryptPrekeyInit(
                bob.identity.identityPrivate(), bob.signed.privateHandle(), bob.resolver(),
                null, first.envelopeCiphertext());

        // Simulate store reload: raw blob bytes round-trip. The reply goes
        // first (ratchet-eligible immediately), then Alice's next message is
        // a true whisper now that she has received a reply.
        byte[] aliceReloaded = Arrays.copyOf(
                first.updatedSessionBlob(), first.updatedSessionBlob().length);
        byte[] bobReloaded = Arrays.copyOf(
                inbound.updatedSessionBlob(), inbound.updatedSessionBlob().length);

        SignalAdapter.EncryptResult reply =
                bob.adapter.encrypt(bobReloaded, bytes("reply-after-reload"));
        SignalAdapter.DecryptResult replyBack =
                alice.adapter.decrypt(aliceReloaded, reply.envelopeCiphertext());
        assertArrayEquals(bytes("reply-after-reload"), replyBack.plaintextAssoc());

        SignalAdapter.EncryptResult next =
                alice.adapter.encrypt(replyBack.updatedSessionBlob(), bytes("after-reload"));
        assertEquals(CryptoTypes.EnvelopeType.RATCHET, next.envelopeType());
        SignalAdapter.DecryptResult got =
                bob.adapter.decrypt(reply.updatedSessionBlob(), next.envelopeCiphertext());
        assertArrayEquals(bytes("after-reload"), got.plaintextAssoc());
    }

    private static CryptoTypes.RecipientBundle aliceBundle(Device alice) {
        SignalAdapter.OneTimePrekeyPair otp = alice.otpks.isEmpty() ? null : alice.otpks.get(0);
        return LibSignalAdapterTest.bundle(alice.deviceId, alice.userId,
                alice.identity.identityPublicKey(), alice.signed.publicKey(),
                alice.signed.signature(), otp, alice.kyber);
    }
}
