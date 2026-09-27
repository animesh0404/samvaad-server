package com.samvaad.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * The outbound encrypt-to-COMMITTED transition is one durable atomic
 * boundary: the advanced session blob and the committed ciphertext are
 * persisted together via {@link SessionStore#commitOutboundCiphertext}, or
 * neither is. Recovery can never observe a durable
 * session-advanced/ciphertext-missing state, so retry resubmits identical
 * bytes instead of re-encrypting on an advanced ratchet.
 */
class AtomicCommitTest {

    /** ClientCryptoStore spy recording which write path produced each COMMITTED slot. */
    static final class CommitSpyStores implements ClientCryptoStore {
        private final ClientCryptoStore delegate;
        private final AtomicInteger atomicCommits = new AtomicInteger();
        private final AtomicInteger directCommittedSlotWrites = new AtomicInteger();

        CommitSpyStores(ClientCryptoStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void saveSession(CryptoTypes.SessionRecord record) {
            delegate.saveSession(record);
        }

        @Override
        public Optional<CryptoTypes.SessionRecord> loadSession(UUID peerDeviceId) {
            return delegate.loadSession(peerDeviceId);
        }

        @Override
        public void deleteSession(UUID peerDeviceId) {
            delegate.deleteSession(peerDeviceId);
        }

        @Override
        public List<CryptoTypes.SessionRecord> allSessions() {
            return delegate.allSessions();
        }

        @Override
        public void saveSlot(CryptoTypes.OutboundSlot slot) {
            if (slot.state() == CryptoTypes.OutboundSlotState.COMMITTED
                    && slot.envelopeCiphertext() != null) {
                directCommittedSlotWrites.incrementAndGet();
            }
            delegate.saveSlot(slot);
        }

        @Override
        public Optional<CryptoTypes.OutboundSlot> loadSlot(
                UUID messageRequestId, UUID recipientDeviceId) {
            return delegate.loadSlot(messageRequestId, recipientDeviceId);
        }

        @Override
        public List<CryptoTypes.OutboundSlot> slotsForMessage(UUID messageRequestId) {
            return delegate.slotsForMessage(messageRequestId);
        }

        @Override
        public List<CryptoTypes.OutboundSlot> pendingSlots() {
            return delegate.pendingSlots();
        }

        @Override
        public void commitOutboundCiphertext(
                CryptoTypes.SessionRecord advancedSession, CryptoTypes.OutboundSlot committedSlot) {
            atomicCommits.incrementAndGet();
            delegate.commitOutboundCiphertext(advancedSession, committedSlot);
        }

        @Override
        public void commitInboundEstablishment(
                CryptoTypes.SessionRecord inboundSession, Integer consumedOneTimePrekeyIdOrNull) {
            delegate.commitInboundEstablishment(inboundSession, consumedOneTimePrekeyIdOrNull);
        }

        @Override
        public void provision(
                SignalAdapter.LocalIdentity identity, SignalAdapter.SignedPrekeyPair signedPrekey) {
            delegate.provision(identity, signedPrekey);
        }

        @Override
        public boolean isProvisioned() {
            return delegate.isProvisioned();
        }

        @Override
        public UUID ownDeviceId() {
            return delegate.ownDeviceId();
        }

        @Override
        public int registrationId() {
            return delegate.registrationId();
        }

        @Override
        public byte[] identityPublicKey() {
            return delegate.identityPublicKey();
        }

        @Override
        public SignalAdapter.SealedPrivateHandle identityPrivate() {
            return delegate.identityPrivate();
        }

        @Override
        public SignalAdapter.SignedPrekeyPair signedPrekey() {
            return delegate.signedPrekey();
        }

        @Override
        public void putOneTimePrivate(int prekeyId, SignalAdapter.SealedPrivateHandle privateHandle) {
            delegate.putOneTimePrivate(prekeyId, privateHandle);
        }

        @Override
        public Optional<SignalAdapter.SealedPrivateHandle> oneTimePrivate(int prekeyId) {
            return delegate.oneTimePrivate(prekeyId);
        }

        @Override
        public void forgetOneTimePrivate(int prekeyId) {
            delegate.forgetOneTimePrivate(prekeyId);
        }

        @Override
        public CryptoTypes.TrustRecord observe(UUID peerDeviceId, byte[] identityPublicKey) {
            return delegate.observe(peerDeviceId, identityPublicKey);
        }

        @Override
        public Optional<CryptoTypes.TrustRecord> load(UUID peerDeviceId) {
            return delegate.load(peerDeviceId);
        }

        @Override
        public void acceptKeyChange(UUID peerDeviceId, byte[] newIdentityPublicKey) {
            delegate.acceptKeyChange(peerDeviceId, newIdentityPublicKey);
        }

        @Override
        public void rejectKeyChange(UUID peerDeviceId) {
            delegate.rejectKeyChange(peerDeviceId);
        }

        @Override
        public void markRevoked(UUID peerDeviceId) {
            delegate.markRevoked(peerDeviceId);
        }
    }

    /** ClientCryptoStore simulating a crash inside the atomic commit boundary. */
    static final class CrashDuringCommitStores implements ClientCryptoStore {
        private final CommitSpyStores delegate;
        private final AtomicBoolean armed = new AtomicBoolean(true);

        CrashDuringCommitStores(CommitSpyStores delegate) {
            this.delegate = delegate;
        }

        void disarm() {
            armed.set(false);
        }

        @Override
        public void saveSession(CryptoTypes.SessionRecord record) {
            delegate.saveSession(record);
        }

        @Override
        public Optional<CryptoTypes.SessionRecord> loadSession(UUID peerDeviceId) {
            return delegate.loadSession(peerDeviceId);
        }

        @Override
        public void deleteSession(UUID peerDeviceId) {
            delegate.deleteSession(peerDeviceId);
        }

        @Override
        public List<CryptoTypes.SessionRecord> allSessions() {
            return delegate.allSessions();
        }

        @Override
        public void saveSlot(CryptoTypes.OutboundSlot slot) {
            delegate.saveSlot(slot);
        }

        @Override
        public Optional<CryptoTypes.OutboundSlot> loadSlot(
                UUID messageRequestId, UUID recipientDeviceId) {
            return delegate.loadSlot(messageRequestId, recipientDeviceId);
        }

        @Override
        public List<CryptoTypes.OutboundSlot> slotsForMessage(UUID messageRequestId) {
            return delegate.slotsForMessage(messageRequestId);
        }

        @Override
        public List<CryptoTypes.OutboundSlot> pendingSlots() {
            return delegate.pendingSlots();
        }

        @Override
        public void commitOutboundCiphertext(
                CryptoTypes.SessionRecord advancedSession, CryptoTypes.OutboundSlot committedSlot) {
            if (armed.getAndSet(false)) {
                // Crash before anything in this boundary becomes durable:
                // neither the advanced session nor the ciphertext is visible.
                throw new SimulatedCrash("crash inside atomic commit");
            }
            delegate.commitOutboundCiphertext(advancedSession, committedSlot);
        }

        @Override
        public void commitInboundEstablishment(
                CryptoTypes.SessionRecord inboundSession, Integer consumedOneTimePrekeyIdOrNull) {
            delegate.commitInboundEstablishment(inboundSession, consumedOneTimePrekeyIdOrNull);
        }

        @Override
        public void provision(
                SignalAdapter.LocalIdentity identity, SignalAdapter.SignedPrekeyPair signedPrekey) {
            delegate.provision(identity, signedPrekey);
        }

        @Override
        public boolean isProvisioned() {
            return delegate.isProvisioned();
        }

        @Override
        public UUID ownDeviceId() {
            return delegate.ownDeviceId();
        }

        @Override
        public int registrationId() {
            return delegate.registrationId();
        }

        @Override
        public byte[] identityPublicKey() {
            return delegate.identityPublicKey();
        }

        @Override
        public SignalAdapter.SealedPrivateHandle identityPrivate() {
            return delegate.identityPrivate();
        }

        @Override
        public SignalAdapter.SignedPrekeyPair signedPrekey() {
            return delegate.signedPrekey();
        }

        @Override
        public void putOneTimePrivate(int prekeyId, SignalAdapter.SealedPrivateHandle privateHandle) {
            delegate.putOneTimePrivate(prekeyId, privateHandle);
        }

        @Override
        public Optional<SignalAdapter.SealedPrivateHandle> oneTimePrivate(int prekeyId) {
            return delegate.oneTimePrivate(prekeyId);
        }

        @Override
        public void forgetOneTimePrivate(int prekeyId) {
            delegate.forgetOneTimePrivate(prekeyId);
        }

        @Override
        public CryptoTypes.TrustRecord observe(UUID peerDeviceId, byte[] identityPublicKey) {
            return delegate.observe(peerDeviceId, identityPublicKey);
        }

        @Override
        public Optional<CryptoTypes.TrustRecord> load(UUID peerDeviceId) {
            return delegate.load(peerDeviceId);
        }

        @Override
        public void acceptKeyChange(UUID peerDeviceId, byte[] newIdentityPublicKey) {
            delegate.acceptKeyChange(peerDeviceId, newIdentityPublicKey);
        }

        @Override
        public void rejectKeyChange(UUID peerDeviceId) {
            delegate.rejectKeyChange(peerDeviceId);
        }

        @Override
        public void markRevoked(UUID peerDeviceId) {
            delegate.markRevoked(peerDeviceId);
        }
    }

    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String message) {
            super(message);
        }
    }

    @Test
    void committedCiphertextUsesOnlyTheAtomicBoundary() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        CommitSpyStores spy = new CommitSpyStores(h.stores());
        SamvaadCryptoService service = new SamvaadCryptoServiceImpl(
                h.adapter(), spy, h.claimFake(), h.submitFake());

        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);
        UUID msg = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r = service.sendToDevices(msg, sender,
                "m".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r.outcomes().get(peer));

        // The COMMITTED slot (with ciphertext) arrived exclusively through the
        // atomic boundary — never via a separate saveSlot call.
        assertEquals(1, spy.atomicCommits.get());
        assertEquals(0, spy.directCommittedSlotWrites.get());
        assertEquals(CryptoTypes.OutboundSlotState.ACKED,
                h.sessions().loadSlot(msg, peer).orElseThrow().state());
        assertEquals(1, h.sessions().loadSession(peer).orElseThrow().encryptCounter());
    }

    @Test
    void crashDuringCommitLeavesNoPartialStateAndRetryIsDeterministic() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        CrashDuringCommitStores crashing =
                new CrashDuringCommitStores(new CommitSpyStores(h.stores()));
        SamvaadCryptoService service = new SamvaadCryptoServiceImpl(
                h.adapter(), crashing, h.claimFake(), h.submitFake());

        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);
        List<CryptoTypes.RecipientBundle> directory =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9));
        UUID msg = UUID.randomUUID();

        try {
            service.sendToDevices(msg, sender, "m".getBytes(), directory, Set.of());
            fail("expected simulated crash inside the atomic commit");
        } catch (SimulatedCrash expected) {
            // Neither half of the boundary became durable.
        }

        // No durable session-advanced/ciphertext-missing state: the session
        // counter is unadvanced, the slot holds no ciphertext.
        assertEquals(0, h.sessions().loadSession(peer).orElseThrow().encryptCounter());
        CryptoTypes.OutboundSlot slot = h.sessions().loadSlot(msg, peer).orElseThrow();
        assertEquals(CryptoTypes.OutboundSlotState.SESSION_READY, slot.state());
        assertTrue(slot.envelopeCiphertext() == null);

        int claims = h.claimFake().calls();
        int establishes = h.adapter().establishCalls();
        int encrypts = h.adapter().encryptCalls();

        // Recovery over the same durable stores: same claim, same session,
        // one deterministic commit, then an ACKED slot.
        crashing.disarm();
        SamvaadCryptoService recovered = new SamvaadCryptoServiceImpl(
                h.adapter(), crashing, h.claimFake(), h.submitFake());
        SamvaadCryptoService.FanoutResult r =
                recovered.sendToDevices(msg, sender, "m".getBytes(), directory, Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r.outcomes().get(peer));
        assertEquals(claims, h.claimFake().calls());
        assertEquals(establishes, h.adapter().establishCalls());
        assertEquals(encrypts + 1, h.adapter().encryptCalls());
        assertEquals(1, h.sessions().loadSession(peer).orElseThrow().encryptCounter());
        assertEquals(CryptoTypes.OutboundSlotState.ACKED,
                h.sessions().loadSlot(msg, peer).orElseThrow().state());
        assertArrayEquals(h.sessions().loadSlot(msg, peer).orElseThrow().envelopeCiphertext(),
                h.submitFake().batches().get(0).get(0).envelopeCiphertext());
    }

    @Test
    void atomicCommitRejectsCiphertextWithoutSessionAdvancement() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID peer = UUID.randomUUID();
        UUID msg = UUID.randomUUID();
        UUID claimId = CryptoTypes.deriveClaimRequestId(msg, sender, peer);

        // A COMMITTED slot without the matching atomic session advancement is
        // rejected: the boundary refuses to persist ciphertext alone.
        CryptoTypes.OutboundSlot orphan = new CryptoTypes.OutboundSlot(msg, sender, peer, claimId,
                CryptoTypes.OutboundSlotState.COMMITTED, null,
                CryptoTypes.EnvelopeType.PREKEY_INIT, null, "ct".getBytes());
        CryptoTypes.SessionRecord unrelated = new CryptoTypes.SessionRecord(peer,
                CryptoTestFixtures.key("x:id"), 9, CryptoTypes.LocalSessionState.READY, null,
                "blob".getBytes(), 1, 0);
        assertThrows(IllegalStateException.class,
                () -> h.sessions().commitOutboundCiphertext(unrelated, orphan));
        assertTrue(h.sessions().loadSlot(msg, peer).isEmpty());
        assertTrue(h.sessions().loadSession(peer).isEmpty());
    }
}
