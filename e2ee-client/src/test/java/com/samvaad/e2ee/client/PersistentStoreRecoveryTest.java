package com.samvaad.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.samvaad.e2ee.client.fake.FakeSignalAdapter;
import com.samvaad.e2ee.client.persist.FileBackedClientCryptoStore;

/**
 * Production persistence semantics of the {@link ClientCryptoStore}
 * boundary, proven against the file-backed reference implementation with
 * real process-restart-shaped recovery (close, reopen from the same
 * directory with a fresh adapter):
 *
 * <ul>
 *   <li>successful atomic commits are fully visible after reopen;</li>
 *   <li>failure before/during a commit leaves neither half durable;</li>
 *   <li>retry after recovery replays exact bytes without re-encryption;</li>
 *   <li>inbound session + OTPK consumption commit atomically;</li>
 *   <li>trust verdicts survive on canonical key bytes;</li>
 *   <li>no private key bytes ever reach the snapshot file.</li>
 * </ul>
 */
class PersistentStoreRecoveryTest {

    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String message) {
            super(message);
        }
    }

    /** File-backed harness: the server fakes stay live, the client restarts. */
    record FileHarness(
            FakeSignalAdapter adapter,
            FileBackedClientCryptoStore stores,
            CryptoTestFixtures.ClaimFake claims,
            CryptoTestFixtures.SubmitFake submit,
            SamvaadCryptoService service,
            Path dir,
            UUID ownDevice) {
    }

    private static FileHarness freshHarness(Path dir, UUID ownDevice) {
        FakeSignalAdapter adapter = new FakeSignalAdapter();
        FileBackedClientCryptoStore stores =
                FileBackedClientCryptoStore.open(dir, ownDevice, 42);
        stores.provision(
                adapter.generateIdentity(),
                adapter.generateSignedPrekey(
                        new FakeSignalAdapter.FakeHandle(UUID.randomUUID()), 11));
        CryptoTestFixtures.ClaimFake claims = new CryptoTestFixtures.ClaimFake();
        CryptoTestFixtures.SubmitFake submit = new CryptoTestFixtures.SubmitFake();
        SamvaadCryptoService service =
                new SamvaadCryptoServiceImpl(adapter, stores, claims, submit);
        return new FileHarness(adapter, stores, claims, submit, service, dir, ownDevice);
    }

    private static FileHarness reopenHarness(FileHarness before) {
        FakeSignalAdapter adapter = new FakeSignalAdapter();
        FileBackedClientCryptoStore stores =
                FileBackedClientCryptoStore.open(before.dir(), before.ownDevice(), 42);
        SamvaadCryptoService service =
                new SamvaadCryptoServiceImpl(adapter, stores, before.claims(), before.submit());
        return new FileHarness(adapter, stores, before.claims(), before.submit(),
                service, before.dir(), before.ownDevice());
    }

    private static List<SignalAdapter.OneTimePrekeyPair> uploadOtpks(
            FileHarness owner, CryptoTestFixtures.ClaimFake serverView, UUID deviceId,
            int firstId, int count) {
        java.util.ArrayList<SignalAdapter.OneTimePrekeyPair> pairs = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            SignalAdapter.OneTimePrekeyPair pair = owner.adapter().generateOneTimePrekey(firstId + i);
            owner.stores().putOneTimePrivate(pair.prekeyId(), pair.privateHandle());
            pairs.add(pair);
        }
        serverView.uploadOneTimePrekeys(deviceId, pairs);
        return List.copyOf(pairs);
    }

    private static String snapshotText(FileHarness h) throws Exception {
        return Files.readString(
                h.dir().resolve("client-crypto-store-v1.json"), StandardCharsets.UTF_8);
    }

    @Test
    void successfulAtomicCommitSurvivesReopen(@TempDir Path dir) {
        UUID sender = UUID.randomUUID();
        FileHarness h = freshHarness(dir, sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claims().register(bob, peer, 1, "bob-phone", 9);

        UUID msg = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r = h.service().sendToDevices(msg, sender,
                "m".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r.outcomes().get(peer));
        byte[] committed = h.stores().loadSlot(msg, peer).orElseThrow().envelopeCiphertext();

        FileHarness reopened = reopenHarness(h);
        assertTrue(reopened.stores().isProvisioned());
        assertEquals(sender, reopened.stores().ownDeviceId());
        assertEquals(CryptoTypes.OutboundSlotState.ACKED,
                reopened.stores().loadSlot(msg, peer).orElseThrow().state());
        assertArrayEquals(committed,
                reopened.stores().loadSlot(msg, peer).orElseThrow().envelopeCiphertext());
        assertEquals(1, reopened.stores().loadSession(peer).orElseThrow().encryptCounter());
        assertEquals(CryptoTypes.TrustState.TRUSTED,
                reopened.stores().load(peer).orElseThrow().state());
        assertArrayEquals(CryptoTestFixtures.key("bob-phone:id"),
                reopened.stores().load(peer).orElseThrow().identityPublicKey());
    }

    @Test
    void failureBeforeCommitLeavesNothingDurable(@TempDir Path dir) {
        UUID sender = UUID.randomUUID();
        FileHarness h = freshHarness(dir, sender);
        h.stores().setCommitFaultForTesting(() -> {
            throw new SimulatedCrash("crash before commit");
        });
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claims().register(bob, peer, 1, "bob-phone", 9);

        UUID msg = UUID.randomUUID();
        try {
            h.service().sendToDevices(msg, sender, "m".getBytes(),
                    List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());
            fail("expected simulated crash");
        } catch (SimulatedCrash expected) {
            // Nothing in the send boundary became durable.
        }

        FileHarness reopened = reopenHarness(h);
        assertTrue(reopened.stores().loadSlot(msg, peer).isEmpty());
        assertTrue(reopened.stores().loadSession(peer).isEmpty());

        // Clean retry over recovered state succeeds deterministically.
        SamvaadCryptoService.FanoutResult r = reopened.service().sendToDevices(msg, sender,
                "m".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r.outcomes().get(peer));
        assertEquals(CryptoTypes.OutboundSlotState.ACKED,
                reopened.stores().loadSlot(msg, peer).orElseThrow().state());
    }

    @Test
    void crashDuringCommitPreservesPreviousSnapshot(@TempDir Path dir) {
        UUID sender = UUID.randomUUID();
        FileHarness h = freshHarness(dir, sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claims().register(bob, peer, 1, "bob-phone", 9);
        List<CryptoTypes.RecipientBundle> directory =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9));

        UUID msg1 = UUID.randomUUID();
        h.service().sendToDevices(msg1, sender, "one".getBytes(), directory, Set.of());
        assertEquals(1, h.stores().loadSession(peer).orElseThrow().encryptCounter());

        AtomicBoolean armed = new AtomicBoolean(true);
        h.stores().setCommitFaultForTesting(() -> {
            if (armed.getAndSet(false)) {
                throw new SimulatedCrash("crash inside atomic commit");
            }
        });
        UUID msg2 = UUID.randomUUID();
        try {
            h.service().sendToDevices(msg2, sender, "two".getBytes(), directory, Set.of());
            fail("expected simulated crash");
        } catch (SimulatedCrash expected) {
            // The in-process maps may be ahead; only the reopened snapshot counts.
        }

        // Recovery observes the previous complete snapshot: msg1 intact, msg2
        // with no committed half-state, session counter unadvanced.
        FileHarness reopened = reopenHarness(h);
        assertEquals(CryptoTypes.OutboundSlotState.ACKED,
                reopened.stores().loadSlot(msg1, peer).orElseThrow().state());
        assertTrue(reopened.stores().loadSlot(msg2, peer).isEmpty()
                || reopened.stores().loadSlot(msg2, peer).orElseThrow().envelopeCiphertext() == null);
        assertEquals(1, reopened.stores().loadSession(peer).orElseThrow().encryptCounter());

        int claims = reopened.claims().calls();
        SamvaadCryptoService.FanoutResult r = reopened.service().sendToDevices(
                msg2, sender, "two".getBytes(), directory, Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r.outcomes().get(peer));
        assertEquals(claims, reopened.claims().calls());
        assertEquals(0, reopened.adapter().establishCalls());
        assertEquals(1, reopened.adapter().encryptCalls());
        assertEquals(2, reopened.stores().loadSession(peer).orElseThrow().encryptCounter());
        assertArrayEquals(
                reopened.stores().loadSlot(msg2, peer).orElseThrow().envelopeCiphertext(),
                reopened.submit().batches().get(reopened.submit().batches().size() - 1)
                        .get(0).envelopeCiphertext());
    }

    @Test
    void committedSlotReplaysIdenticallyAfterReopenWithoutReencryption(@TempDir Path dir) {
        UUID sender = UUID.randomUUID();
        FileHarness h = freshHarness(dir, sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claims().register(bob, peer, 1, "bob-phone", 9);
        List<CryptoTypes.RecipientBundle> directory =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9));

        UUID msg = UUID.randomUUID();
        h.submit().failNext();
        SamvaadCryptoService.FanoutResult r1 =
                h.service().sendToDevices(msg, sender, "m".getBytes(), directory, Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.DEFERRED_TRANSIENT, r1.outcomes().get(peer));
        byte[] committed = h.stores().loadSlot(msg, peer).orElseThrow().envelopeCiphertext();

        // Rotate the recipient identity while the commit waits for submit.
        h.claims().register(bob, peer, 1, "bob-phone-v2", 9);
        List<CryptoTypes.RecipientBundle> rotated =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone-v2", 9));

        FileHarness reopened = reopenHarness(h);
        int claims = reopened.claims().calls();
        SamvaadCryptoService.FanoutResult r2 =
                reopened.service().sendToDevices(msg, sender, "m".getBytes(), rotated, Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r2.outcomes().get(peer));
        assertEquals(1, reopened.submit().batches().size());
        assertArrayEquals(committed, reopened.submit().batches().get(0).get(0).envelopeCiphertext());
        assertEquals(claims, reopened.claims().calls());
        assertEquals(0, reopened.adapter().establishCalls());
        assertEquals(0, reopened.adapter().encryptCalls());
        assertEquals(1, reopened.stores().loadSession(peer).orElseThrow().encryptCounter());
    }

    @Test
    void inboundOtpkCommitIsAtomicAcrossReopen(@TempDir Path dir) {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness s = CryptoTestFixtures.harness(sender);
        UUID receiver = UUID.randomUUID();
        FileHarness r = freshHarness(dir, receiver);

        UUID bob = UUID.randomUUID();
        s.claimFake().register(bob, receiver, 1, "receiver-phone", 9);
        List<SignalAdapter.OneTimePrekeyPair> issued =
                uploadOtpks(r, s.claimFake(), receiver, 100, 2);

        UUID msg = UUID.randomUUID();
        s.service().sendToDevices(msg, sender, "hello".getBytes(),
                List.of(CryptoTestFixtures.listed(bob, receiver, 1, "receiver-phone", 9)), Set.of());
        byte[] envelope = s.submitFake().batches().get(0).get(0).envelopeCiphertext();
        r.service().decrypt(sender, receiver, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope);

        FileHarness reopened = reopenHarness(r);
        // Both halves durable: session READY, referenced OTPK consumed, other intact.
        assertEquals(CryptoTypes.LocalSessionState.READY,
                reopened.stores().loadSession(receiver).orElseThrow().state());
        assertTrue(reopened.stores().oneTimePrivate(issued.get(0).prekeyId()).isEmpty());
        assertTrue(reopened.stores().oneTimePrivate(issued.get(1).prekeyId()).isPresent());
        assertThrows(CryptoException.ClaimFailedException.class,
                () -> reopened.stores().requireOneTimePrivate(issued.get(0).prekeyId()));

        // Replay after recovery fails closed without touching the other OTPK.
        assertThrows(CryptoException.ClaimFailedException.class, () -> reopened.service().decrypt(
                sender, receiver, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope));
        assertTrue(reopened.stores().oneTimePrivate(issued.get(1).prekeyId()).isPresent());
    }

    @Test
    void crashDuringInboundCommitLeavesNeitherHalfDurable(@TempDir Path dir) {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness s = CryptoTestFixtures.harness(sender);
        UUID receiver = UUID.randomUUID();
        FileHarness r = freshHarness(dir, receiver);

        UUID bob = UUID.randomUUID();
        s.claimFake().register(bob, receiver, 1, "receiver-phone", 9);
        List<SignalAdapter.OneTimePrekeyPair> issued =
                uploadOtpks(r, s.claimFake(), receiver, 100, 2);
        // Disarm the upload persists: only the inbound commit may fail.
        AtomicBoolean armed = new AtomicBoolean(true);
        r.stores().setCommitFaultForTesting(() -> {
            if (armed.getAndSet(false)) {
                throw new SimulatedCrash("crash inside inbound commit");
            }
        });

        UUID msg = UUID.randomUUID();
        s.service().sendToDevices(msg, sender, "hello".getBytes(),
                List.of(CryptoTestFixtures.listed(bob, receiver, 1, "receiver-phone", 9)), Set.of());
        byte[] envelope = s.submitFake().batches().get(0).get(0).envelopeCiphertext();
        try {
            r.service().decrypt(sender, receiver, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope);
            fail("expected simulated crash");
        } catch (SimulatedCrash expected) {
            // Neither the session nor the consumption became durable.
        }

        FileHarness reopened = reopenHarness(r);
        assertTrue(reopened.stores().loadSession(receiver).isEmpty());
        // The OTPK was NOT consumed: retry re-resolves the same handle.
        assertTrue(reopened.stores().oneTimePrivate(issued.get(0).prekeyId()).isPresent());
        reopened.service().decrypt(sender, receiver, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope);
        assertEquals(CryptoTypes.LocalSessionState.READY,
                reopened.stores().loadSession(receiver).orElseThrow().state());
        assertTrue(reopened.stores().oneTimePrivate(issued.get(0).prekeyId()).isEmpty());
        assertTrue(reopened.stores().oneTimePrivate(issued.get(1).prekeyId()).isPresent());
    }

    @Test
    void trustVerdictsSurviveReopenOnCanonicalBytes(@TempDir Path dir) {
        UUID sender = UUID.randomUUID();
        FileHarness h = freshHarness(dir, sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claims().register(bob, peer, 1, "bob-phone", 9);

        UUID msg1 = UUID.randomUUID();
        h.service().sendToDevices(msg1, sender, "hi".getBytes(),
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());

        h.claims().register(bob, peer, 1, "bob-phone-v2", 9);
        UUID msg2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult paused = h.service().sendToDevices(msg2, sender,
                "hi2".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone-v2", 9)),
                Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.PAUSED_KEY_CHANGED, paused.outcomes().get(peer));

        FileHarness reopened = reopenHarness(h);
        CryptoTypes.TrustRecord record = reopened.stores().load(peer).orElseThrow();
        assertEquals(CryptoTypes.TrustState.PAUSED_KEY_CHANGED, record.state());
        assertArrayEquals(CryptoTestFixtures.key("bob-phone:id"), record.identityPublicKey());

        byte[] rotated = CryptoTestFixtures.key("bob-phone-v2:id");
        reopened.service().acceptKeyChange(peer, rotated);
        FileHarness again = reopenHarness(reopened);
        assertEquals(CryptoTypes.TrustState.TRUSTED, again.stores().load(peer).orElseThrow().state());
        assertArrayEquals(rotated, again.stores().load(peer).orElseThrow().identityPublicKey());
    }

    @Test
    void snapshotPersistsHandleReferencesAndNeverKeyBytes(@TempDir Path dir) throws Exception {
        UUID sender = UUID.randomUUID();
        FileHarness h = freshHarness(dir, sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claims().register(bob, peer, 1, "bob-phone", 9);
        uploadOtpks(h, h.claims(), peer, 100, 2);

        UUID msg = UUID.randomUUID();
        h.service().sendToDevices(msg, sender, "m".getBytes(),
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());

        String snapshot = snapshotText(h);
        // Versioned envelope, opaque handle references, public bytes — and no
        // private-key field of any name.
        assertTrue(snapshot.contains("\"formatVersion\":" + CryptoTypes.STORE_FORMAT_VERSION));
        assertTrue(snapshot.contains("\"identityHandle\""));
        assertTrue(snapshot.contains("\"signedHandle\""));
        assertTrue(snapshot.contains("\"oneTimePrivates\""));
        assertTrue(!snapshot.contains("privateKey"));
        assertTrue(!snapshot.contains("identityPrivate"));
        assertTrue(!snapshot.contains("privateHandle"));
        assertTrue(!snapshot.contains("privateBytes"));
        // Handle identities round-trip as the same UUID references.
        UUID identityHandle = h.stores().identityPrivate().handleId();
        assertTrue(snapshot.contains(identityHandle.toString()));
    }

    @Test
    void fileStoreRejectsDirectCommittedWritesAndForeignSnapshots(
            @TempDir Path dir, @TempDir Path otherDir) throws Exception {
        UUID sender = UUID.randomUUID();
        FileHarness h = freshHarness(dir, sender);
        UUID peer = UUID.randomUUID();
        UUID msg = UUID.randomUUID();
        UUID claimId = CryptoTypes.deriveClaimRequestId(msg, sender, peer);

        CryptoTypes.OutboundSlot committed = new CryptoTypes.OutboundSlot(msg, sender, peer, claimId,
                CryptoTypes.OutboundSlotState.COMMITTED, null,
                CryptoTypes.EnvelopeType.PREKEY_INIT, null, "ct".getBytes());
        assertThrows(IllegalStateException.class, () -> h.stores().saveSlot(committed));

        // A snapshot with an unknown version is refused, never migrated.
        Path file = dir.resolve("client-crypto-store-v1.json");
        String tampered = Files.readString(file, StandardCharsets.UTF_8)
                .replace("\"formatVersion\":" + CryptoTypes.STORE_FORMAT_VERSION,
                        "\"formatVersion\":999");
        Files.writeString(file, tampered, StandardCharsets.UTF_8);
        assertThrows(PersistentStoreException.class,
                () -> FileBackedClientCryptoStore.open(dir, sender, 42));

        // A different device must not open an intact foreign store.
        FileBackedClientCryptoStore.open(otherDir, sender, 42);
        assertThrows(PersistentStoreException.class,
                () -> FileBackedClientCryptoStore.open(otherDir, UUID.randomUUID(), 42));
    }
}
