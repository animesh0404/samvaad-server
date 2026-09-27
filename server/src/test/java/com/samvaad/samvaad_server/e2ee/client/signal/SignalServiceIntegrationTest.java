package com.samvaad.samvaad_server.e2ee.client.signal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.samvaad.samvaad_server.e2ee.client.CryptoException;
import com.samvaad.samvaad_server.e2ee.client.CryptoTestFixtures;
import com.samvaad.samvaad_server.e2ee.client.CryptoTypes;
import com.samvaad.samvaad_server.e2ee.client.SamvaadCryptoService;
import com.samvaad.samvaad_server.e2ee.client.SamvaadCryptoServiceImpl;
import com.samvaad.samvaad_server.e2ee.client.SignalAdapter;
import com.samvaad.samvaad_server.e2ee.client.persist.FileBackedClientCryptoStore;

/**
 * Section-14 proof: the real adapter works through
 * {@link SamvaadCryptoService} and the file-backed {@code ClientCryptoStore},
 * across store restarts. Outbound commits replay byte-identically after a
 * restart without touching the adapter; inbound sessions keep decrypting
 * ratchet messages after a true restart (fresh adapter, empty private-key
 * registry); new private-key operations after a true restart fail closed.
 */
class SignalServiceIntegrationTest {

    /** One file-backed real device behind the Samvaad service. */
    record FileService(
            LibSignalAdapter adapter,
            FileBackedClientCryptoStore stores,
            CryptoTestFixtures.ClaimFake claims,
            CryptoTestFixtures.SubmitFake submit,
            SamvaadCryptoService service,
            Path dir,
            UUID ownDevice,
            UUID ownUser,
            int regId,
            byte[] identityPublic,
            SignalAdapter.SignedPrekeyPair signed,
            SignalAdapter.KyberPrekeyPair kyber) {

        CryptoTypes.RecipientBundle bundle(SignalAdapter.OneTimePrekeyPair otp) {
            return new CryptoTypes.RecipientBundle(ownDevice, ownUser, 1, regId, identityPublic,
                    signed.prekeyId(), signed.publicKey(), signed.signature(),
                    otp == null ? null : otp.prekeyId(), otp == null ? null : otp.publicKey(),
                    kyber.prekeyId(), kyber.publicKey(), kyber.signature());
        }

        SignalAdapter.OneTimePrekeyPair issueOtpk(int id) {
            SignalAdapter.OneTimePrekeyPair pair = adapter.generateOneTimePrekey(id);
            stores.putOneTimePrivate(id, pair.privateHandle());
            return pair;
        }
    }

    private static FileService freshService(Path dir, UUID ownDevice, UUID ownUser, int regId) {
        LibSignalAdapter adapter = new LibSignalAdapter(regId);
        FileBackedClientCryptoStore stores =
                FileBackedClientCryptoStore.open(dir, ownDevice, regId);
        SignalAdapter.LocalIdentity identity = adapter.generateIdentity();
        SignalAdapter.SignedPrekeyPair signed =
                adapter.generateSignedPrekey(identity.identityPrivate(), 11);
        stores.provision(identity, signed);
        SignalAdapter.KyberPrekeyPair kyber =
                adapter.generateKyberPrekey(stores.identityPrivate(), 22);
        CryptoTestFixtures.ClaimFake claims = new CryptoTestFixtures.ClaimFake();
        CryptoTestFixtures.SubmitFake submit = new CryptoTestFixtures.SubmitFake();
        SamvaadCryptoService service =
                new SamvaadCryptoServiceImpl(adapter, stores, claims, submit);
        return new FileService(adapter, stores, claims, submit, service, dir, ownDevice, ownUser,
                regId, stores.identityPublicKey(), stores.signedPrekey(), kyber);
    }

    private static FileService reopenService(FileService before, boolean freshAdapter) {
        LibSignalAdapter adapter =
                freshAdapter ? new LibSignalAdapter(before.regId()) : before.adapter();
        FileBackedClientCryptoStore stores = FileBackedClientCryptoStore.open(
                before.dir(), before.ownDevice(), before.regId());
        SamvaadCryptoService service =
                new SamvaadCryptoServiceImpl(adapter, stores, before.claims(), before.submit());
        return new FileService(adapter, stores, before.claims(), before.submit(), service,
                before.dir(), before.ownDevice(), before.ownUser(), before.regId(),
                stores.identityPublicKey(), stores.signedPrekey(), before.kyber());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void realOutboundCommitRestartReplayDelivers(@TempDir Path aliceDir, @TempDir Path bobDir) {
        UUID aliceDevice = UUID.randomUUID();
        UUID bobDevice = UUID.randomUUID();
        FileService alice = freshService(aliceDir, aliceDevice, UUID.randomUUID(), 1001);
        FileService bob = freshService(bobDir, bobDevice, UUID.randomUUID(), 1002);

        SignalAdapter.OneTimePrekeyPair bobOtp = bob.issueOtpk(100);
        alice.claims().pinBundle(bobDevice, bob.bundle(bobOtp));

        // First message end to end through both services.
        UUID msg1 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r1 = alice.service().sendToDevices(msg1, aliceDevice,
                bytes("hello-bob"), List.of(bob.bundle(bobOtp)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r1.outcomes().get(bobDevice));
        assertEquals(CryptoTypes.EstablishmentMode.WITH_ONE_TIME_PREKEY,
                alice.stores().loadSlot(msg1, bobDevice).orElseThrow().establishmentMode());
        byte[] wire1 = alice.submit().batches().get(0).get(0).envelopeCiphertext();
        assertEquals(CryptoTypes.EnvelopeType.PREKEY_INIT,
                alice.submit().batches().get(0).get(0).envelopeType());
        byte[] plain1 = bob.service().decrypt(aliceDevice, aliceDevice,
                CryptoTypes.EnvelopeType.PREKEY_INIT, wire1);
        assertArrayEquals(bytes("hello-bob"), plain1);
        assertTrue(bob.stores().oneTimePrivate(100).isEmpty());

        // Second message commits but the submit fails; restart the store
        // (same process adapter) and retry: byte-identical replay, correctly
        // labeled as a prekey repeat by the producer.
        UUID msg2 = UUID.randomUUID();
        alice.submit().failNext();
        SamvaadCryptoService.FanoutResult r2 = alice.service().sendToDevices(msg2, aliceDevice,
                bytes("second"), List.of(bob.bundle(null)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.DEFERRED_TRANSIENT,
                r2.outcomes().get(bobDevice));
        byte[] committed =
                alice.stores().loadSlot(msg2, bobDevice).orElseThrow().envelopeCiphertext();

        FileService reopened = reopenService(alice, false);
        int claims = reopened.claims().calls();
        SamvaadCryptoService.FanoutResult r3 = reopened.service().sendToDevices(msg2, aliceDevice,
                bytes("second"), List.of(bob.bundle(null)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r3.outcomes().get(bobDevice));
        assertEquals(claims, reopened.claims().calls());
        CryptoTypes.OutboundEnvelope replayed = reopened.submit().batches()
                .get(reopened.submit().batches().size() - 1).get(0);
        assertArrayEquals(committed, replayed.envelopeCiphertext());
        assertEquals(CryptoTypes.EnvelopeType.PREKEY_INIT, replayed.envelopeType());

        // The repeat references the consumed OTPK: deterministic rejection
        // with the session intact (pre-reply rapid-send window, deferred to
        // a future duplicate-PREKEY_INIT slice).
        assertThrows(CryptoException.ClaimFailedException.class, () -> bob.service().decrypt(
                aliceDevice, aliceDevice, CryptoTypes.EnvelopeType.PREKEY_INIT,
                replayed.envelopeCiphertext()));

        // Bob replies via his own outbound establishment; Alice converges;
        // whisper messaging resumes and decrypts on both sides. The OTPK is
        // issued through the reopened store so its maps stay current.
        SignalAdapter.OneTimePrekeyPair aliceOtp = reopened.issueOtpk(300);
        bob.claims().pinBundle(aliceDevice, reopened.bundle(aliceOtp));
        UUID replyId = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult replyResult = bob.service().sendToDevices(replyId,
                bobDevice, bytes("reply"), List.of(alice.bundle(null)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT,
                replyResult.outcomes().get(aliceDevice));
        byte[] replyWire = bob.submit().batches().get(bob.submit().batches().size() - 1)
                .get(0).envelopeCiphertext();
        assertEquals(CryptoTypes.EnvelopeType.PREKEY_INIT, bob.submit().batches()
                .get(bob.submit().batches().size() - 1).get(0).envelopeType());
        assertArrayEquals(bytes("reply"), reopened.service().decrypt(bobDevice, bobDevice,
                CryptoTypes.EnvelopeType.PREKEY_INIT, replyWire));

        UUID msg3 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r4 = reopened.service().sendToDevices(msg3, aliceDevice,
                bytes("third"), List.of(bob.bundle(null)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r4.outcomes().get(bobDevice));
        CryptoTypes.OutboundEnvelope third = reopened.submit().batches()
                .get(reopened.submit().batches().size() - 1).get(0);
        assertEquals(CryptoTypes.EnvelopeType.RATCHET, third.envelopeType());
        assertArrayEquals(bytes("third"), bob.service().decrypt(aliceDevice, aliceDevice,
                CryptoTypes.EnvelopeType.RATCHET, third.envelopeCiphertext()));
    }

    @Test
    void realReplayNeedsNoCryptoAfterRestart(@TempDir Path aliceDir, @TempDir Path bobDir) {
        UUID aliceDevice = UUID.randomUUID();
        UUID bobDevice = UUID.randomUUID();
        FileService alice = freshService(aliceDir, aliceDevice, UUID.randomUUID(), 1001);
        FileService bob = freshService(bobDir, bobDevice, UUID.randomUUID(), 1002);

        SignalAdapter.OneTimePrekeyPair bobOtp = bob.issueOtpk(100);
        alice.claims().pinBundle(bobDevice, bob.bundle(bobOtp));

        UUID msg = UUID.randomUUID();
        alice.submit().failNext();
        SamvaadCryptoService.FanoutResult r1 = alice.service().sendToDevices(msg, aliceDevice,
                bytes("m"), List.of(bob.bundle(bobOtp)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.DEFERRED_TRANSIENT,
                r1.outcomes().get(bobDevice));
        byte[] committed =
                alice.stores().loadSlot(msg, bobDevice).orElseThrow().envelopeCiphertext();

        // Fresh adapter with an empty private-key registry: the retry must
        // succeed purely from the committed slot. Any crypto touch would fail
        // closed on the missing private material instead.
        FileService restarted = reopenService(alice, true);
        SamvaadCryptoService.FanoutResult r2 = restarted.service().sendToDevices(msg, aliceDevice,
                bytes("m"), List.of(bob.bundle(null)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r2.outcomes().get(bobDevice));
        assertArrayEquals(committed, restarted.submit().batches()
                .get(restarted.submit().batches().size() - 1).get(0).envelopeCiphertext());
    }

    @Test
    void realInboundSessionUsableAfterTrueRestart(@TempDir Path aliceDir, @TempDir Path bobDir) {
        UUID aliceDevice = UUID.randomUUID();
        UUID bobDevice = UUID.randomUUID();
        FileService alice = freshService(aliceDir, aliceDevice, UUID.randomUUID(), 1001);
        FileService bob = freshService(bobDir, bobDevice, UUID.randomUUID(), 1002);

        SignalAdapter.OneTimePrekeyPair bobOtp = bob.issueOtpk(100);
        SignalAdapter.OneTimePrekeyPair bobSpare = bob.issueOtpk(101);
        alice.claims().pinBundle(bobDevice, bob.bundle(bobOtp));

        UUID msg1 = UUID.randomUUID();
        alice.service().sendToDevices(msg1, aliceDevice, bytes("one"),
                List.of(bob.bundle(bobOtp)), Set.of());
        byte[] wire1 = alice.submit().batches().get(0).get(0).envelopeCiphertext();
        assertArrayEquals(bytes("one"), bob.service().decrypt(aliceDevice, aliceDevice,
                CryptoTypes.EnvelopeType.PREKEY_INIT, wire1));

        // Bob replies before any restart, advancing Alice past the prekey
        // phase so her next message is a true ratchet message.
        SignalAdapter.OneTimePrekeyPair aliceOtp = alice.issueOtpk(300);
        bob.claims().pinBundle(aliceDevice, alice.bundle(aliceOtp));
        UUID replyId = UUID.randomUUID();
        bob.service().sendToDevices(replyId, bobDevice, bytes("reply"),
                List.of(alice.bundle(null)), Set.of());
        byte[] replyWire = bob.submit().batches().get(bob.submit().batches().size() - 1)
                .get(0).envelopeCiphertext();
        assertArrayEquals(bytes("reply"), alice.service().decrypt(bobDevice, bobDevice,
                CryptoTypes.EnvelopeType.PREKEY_INIT, replyWire));

        // True restart of Bob: fresh adapter, empty private-key registry.
        FileService restartedBob = reopenService(bob, true);
        assertTrue(restartedBob.stores().loadSession(aliceDevice).isPresent());

        // Alice's next message is a whisper; it decrypts with the reloaded
        // blob alone — no private keys involved.
        UUID msg2 = UUID.randomUUID();
        alice.service().sendToDevices(msg2, aliceDevice, bytes("two"),
                List.of(bob.bundle(null)), Set.of());
        CryptoTypes.OutboundEnvelope sent = alice.submit().batches()
                .get(alice.submit().batches().size() - 1).get(0);
        assertEquals(CryptoTypes.EnvelopeType.RATCHET, sent.envelopeType());
        assertArrayEquals(bytes("two"), restartedBob.service().decrypt(aliceDevice, aliceDevice,
                CryptoTypes.EnvelopeType.RATCHET, sent.envelopeCiphertext()));

        // Replay of the consumed prekey init still fails closed, spare intact.
        assertThrows(CryptoException.ClaimFailedException.class, () -> restartedBob.service()
                .decrypt(aliceDevice, aliceDevice, CryptoTypes.EnvelopeType.PREKEY_INIT, wire1));
        assertTrue(restartedBob.stores().oneTimePrivate(bobSpare.prekeyId()).isPresent());

        // A fresh prekey init needs private material: fails closed as
        // corruption (quarantined, never half-applied).
        UUID carolDevice = UUID.randomUUID();
        LibSignalAdapter carolAdapter = new LibSignalAdapter(1003);
        SignalAdapter.LocalIdentity carolId = carolAdapter.generateIdentity();
        SignalAdapter.EstablishedSession carolOut = carolAdapter.establishOutbound(
                carolId.identityPrivate(), restartedBob.bundle(bobSpare));
        SignalAdapter.EncryptResult carolWire =
                carolAdapter.encrypt(carolOut.sessionBlob(), bytes("carol-hi"));
        assertThrows(CryptoException.SessionCorruptException.class, () -> restartedBob.service()
                .decrypt(carolDevice, carolDevice, CryptoTypes.EnvelopeType.PREKEY_INIT,
                        carolWire.envelopeCiphertext()));

        // A new send on the established session needs no private keys and
        // succeeds; a send to a brand-new peer needs establishment and fails
        // closed deterministically, fan-out intact.
        UUID msg3 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r3 = restartedBob.service().sendToDevices(msg3,
                bobDevice, bytes("reply"), List.of(alice.bundle(null)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r3.outcomes().get(aliceDevice));
        CryptoTypes.OutboundEnvelope back = restartedBob.submit().batches()
                .get(restartedBob.submit().batches().size() - 1).get(0);
        assertEquals(CryptoTypes.EnvelopeType.RATCHET, back.envelopeType());
        assertArrayEquals(bytes("reply"), alice.service().decrypt(bobDevice, bobDevice,
                CryptoTypes.EnvelopeType.RATCHET, back.envelopeCiphertext()));

        LibSignalAdapter daveAdapter = new LibSignalAdapter(1004);
        SignalAdapter.LocalIdentity daveId = daveAdapter.generateIdentity();
        SignalAdapter.SignedPrekeyPair daveSpk =
                daveAdapter.generateSignedPrekey(daveId.identityPrivate(), 11);
        SignalAdapter.KyberPrekeyPair daveKy =
                daveAdapter.generateKyberPrekey(daveId.identityPrivate(), 22);
        UUID daveDevice = UUID.randomUUID();
        CryptoTypes.RecipientBundle daveBundle = new CryptoTypes.RecipientBundle(daveDevice,
                UUID.randomUUID(), 1, 1004, daveId.identityPublicKey(), 11, daveSpk.publicKey(),
                daveSpk.signature(), null, null, 22, daveKy.publicKey(), daveKy.signature());
        bob.claims().pinBundle(daveDevice, daveBundle);
        UUID msg4 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r4 = restartedBob.service().sendToDevices(msg4,
                bobDevice, bytes("hello-dave"), List.of(daveBundle), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.FAILED_CORRUPT,
                r4.outcomes().get(daveDevice));
    }

    @Test
    void realKeyChangePausesAndAcceptResumes(@TempDir Path aliceDir, @TempDir Path bobDir) {
        UUID aliceDevice = UUID.randomUUID();
        UUID bobDevice = UUID.randomUUID();
        FileService alice = freshService(aliceDir, aliceDevice, UUID.randomUUID(), 1001);
        FileService bob = freshService(bobDir, bobDevice, UUID.randomUUID(), 1002);

        SignalAdapter.OneTimePrekeyPair bobOtp = bob.issueOtpk(100);
        alice.claims().pinBundle(bobDevice, bob.bundle(bobOtp));
        UUID msg1 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r1 = alice.service().sendToDevices(msg1, aliceDevice,
                bytes("hi"), List.of(bob.bundle(bobOtp)), Set.of());
        assertEquals(1, r1.sentCount());

        // Bob rotates his identity out of band; Alice's next message pauses.
        FileService rotatedBob = freshService(bobDir.resolve("rotated"), bobDevice,
                bob.ownUser(), 1002);
        SignalAdapter.OneTimePrekeyPair rotatedOtp = rotatedBob.issueOtpk(200);
        UUID msg2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r2 = alice.service().sendToDevices(msg2, aliceDevice,
                bytes("hi2"), List.of(rotatedBob.bundle(rotatedOtp)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.PAUSED_KEY_CHANGED,
                r2.outcomes().get(bobDevice));

        // Explicit verification of the canonical new key resumes messaging.
        alice.claims().pinBundle(bobDevice, rotatedBob.bundle(rotatedOtp));
        alice.service().acceptKeyChange(bobDevice, rotatedBob.identityPublic());
        UUID msg3 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r3 = alice.service().sendToDevices(msg3, aliceDevice,
                bytes("hi3"), List.of(rotatedBob.bundle(rotatedOtp)), Set.of());
        assertEquals(1, r3.sentCount());
        byte[] wire3 = alice.submit().batches().get(alice.submit().batches().size() - 1)
                .get(0).envelopeCiphertext();
        assertArrayEquals(bytes("hi3"), rotatedBob.service().decrypt(aliceDevice, aliceDevice,
                CryptoTypes.EnvelopeType.PREKEY_INIT, wire3));
    }
}
