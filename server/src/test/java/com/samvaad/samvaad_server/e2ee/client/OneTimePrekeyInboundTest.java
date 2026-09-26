package com.samvaad.samvaad_server.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Inbound prekey-init OTPK contract: the Samvaad orchestration resolves the
 * private handle for exactly the OTPK ID referenced by the incoming envelope
 * (parsed by the adapter from its own typed fields), consumes it exactly
 * once on successful establishment, and rejects unknown/already-consumed IDs
 * deterministically without touching any other OTPK. Raw private key bytes
 * never cross the service boundary — only sealed handles.
 */
class OneTimePrekeyInboundTest {

    @Test
    void registeredHandleResolvesByExactId() {
        UUID self = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(self);

        SignalAdapter.OneTimePrekeyPair pair = h.adapter().generateOneTimePrekey(77);
        h.keys().putOneTimePrivate(pair.prekeyId(), pair.privateHandle());

        assertSame(pair.privateHandle(), h.keys().requireOneTimePrivate(77));
        assertThrows(CryptoException.ClaimFailedException.class,
                () -> h.keys().requireOneTimePrivate(78));
    }

    @Test
    void validOtpkEstablishesAndConsumesExactlyOnce() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        CryptoTestFixtures.Harness s = CryptoTestFixtures.harness(sender);
        CryptoTestFixtures.Harness r = CryptoTestFixtures.harness(receiver);

        UUID bob = UUID.randomUUID();
        UUID peer = receiver; // receiver device as seen by the sender
        s.claimFake().register(bob, peer, 1, "receiver-phone", 9);
        List<SignalAdapter.OneTimePrekeyPair> issued =
                CryptoTestFixtures.uploadOtpks(r, s.claimFake(), peer, 100, 3);

        UUID msg = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult result = s.service().sendToDevices(msg, sender,
                "hello".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "receiver-phone", 9)),
                Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, result.outcomes().get(peer));
        // One server OTPK consumed for the slot; the first issued ID was used.
        assertEquals(2, s.claimFake().availableOneTimePrekeys(peer));

        byte[] envelope = s.submitFake().batches().get(0).get(0).envelopeCiphertext();
        r.service().decrypt(sender, peer, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope);

        // Exactly-once consumption: the referenced OTPK is gone, the others remain.
        assertTrue(r.keys().oneTimePrivate(issued.get(0).prekeyId()).isEmpty());
        assertTrue(r.keys().oneTimePrivate(issued.get(1).prekeyId()).isPresent());
        assertTrue(r.keys().oneTimePrivate(issued.get(2).prekeyId()).isPresent());
        assertEquals(CryptoTypes.LocalSessionState.READY,
                r.sessions().loadSession(peer).orElseThrow().state());
    }

    @Test
    void replayAfterConsumptionIsRejectedWithoutTouchingOtherOtpks() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        CryptoTestFixtures.Harness s = CryptoTestFixtures.harness(sender);
        CryptoTestFixtures.Harness r = CryptoTestFixtures.harness(receiver);

        UUID bob = UUID.randomUUID();
        UUID peer = receiver;
        s.claimFake().register(bob, peer, 1, "receiver-phone", 9);
        List<SignalAdapter.OneTimePrekeyPair> issued =
                CryptoTestFixtures.uploadOtpks(r, s.claimFake(), peer, 100, 3);

        UUID msg = UUID.randomUUID();
        s.service().sendToDevices(msg, sender, "hello".getBytes(),
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "receiver-phone", 9)), Set.of());
        byte[] envelope = s.submitFake().batches().get(0).get(0).envelopeCiphertext();
        r.service().decrypt(sender, peer, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope);

        // Replay of the same envelope: deterministic rejection, no new consumption.
        assertThrows(CryptoException.ClaimFailedException.class,
                () -> r.service().decrypt(sender, peer, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope));
        assertThrows(CryptoException.ClaimFailedException.class,
                () -> r.service().decrypt(sender, peer, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope));
        assertTrue(r.keys().oneTimePrivate(issued.get(1).prekeyId()).isPresent());
        assertTrue(r.keys().oneTimePrivate(issued.get(2).prekeyId()).isPresent());
        // The converged session survives the rejected replay.
        assertEquals(CryptoTypes.LocalSessionState.READY,
                r.sessions().loadSession(peer).orElseThrow().state());
    }

    @Test
    void signedFallbackDecryptConsumesNothing() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        CryptoTestFixtures.Harness s = CryptoTestFixtures.harness(sender);
        CryptoTestFixtures.Harness r = CryptoTestFixtures.harness(receiver);

        // Empty server pool: the claim falls back to the signed prekey, so
        // the envelope carries no OTPK reference and inbound establishment
        // resolves (and consumes) nothing.
        UUID bob = UUID.randomUUID();
        UUID peer = receiver;
        s.claimFake().register(bob, peer, 1, "receiver-phone", 9);
        CryptoTestFixtures.uploadOtpks(r, r.claimFake(), peer, 100, 2);
        UUID msg = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult result = s.service().sendToDevices(msg, sender,
                "hello".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "receiver-phone", 9)),
                Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, result.outcomes().get(peer));
        assertEquals(CryptoTypes.EstablishmentMode.SIGNED_PREKEY_FALLBACK,
                s.sessions().loadSlot(msg, peer).orElseThrow().establishmentMode());
        byte[] envelope = s.submitFake().batches().get(0).get(0).envelopeCiphertext();
        r.service().decrypt(sender, peer, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope);
        assertEquals(CryptoTypes.LocalSessionState.READY,
                r.sessions().loadSession(peer).orElseThrow().state());
        // The receiver's own uploaded OTPKs are untouched by the fallback.
        assertTrue(r.keys().oneTimePrivate(100).isPresent());
        assertTrue(r.keys().oneTimePrivate(101).isPresent());
    }

    @Test
    void envelopeReferencingForeignOtpkFailsClosed() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        CryptoTestFixtures.Harness s = CryptoTestFixtures.harness(sender);
        CryptoTestFixtures.Harness r = CryptoTestFixtures.harness(receiver);

        UUID bob = UUID.randomUUID();
        UUID peer = receiver;
        s.claimFake().register(bob, peer, 1, "receiver-phone", 9);
        // Receiver issued OTPKs 100..101, but the sender's server view is
        // stocked from a DIFFERENT batch (ids 500..501) whose privates the
        // receiver never held: the referenced IDs are unknown to the receiver.
        CryptoTestFixtures.uploadOtpks(r, s.claimFake(), peer, 100, 2);
        CryptoTestFixtures.Harness stranger = CryptoTestFixtures.harness(UUID.randomUUID());
        List<SignalAdapter.OneTimePrekeyPair> foreign =
                CryptoTestFixtures.uploadOtpks(stranger, s.claimFake(), peer, 500, 2);
        // Drain the receiver's own entries first so the claim takes a foreign ID.
        s.claimFake().claim(peer, UUID.randomUUID());
        s.claimFake().claim(peer, UUID.randomUUID());

        UUID msg = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult result = s.service().sendToDevices(msg, sender,
                "hello".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "receiver-phone", 9)),
                Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, result.outcomes().get(peer));
        byte[] envelope = s.submitFake().batches().get(0).get(0).envelopeCiphertext();

        CryptoException.ClaimFailedException first = assertThrows(
                CryptoException.ClaimFailedException.class,
                () -> r.service().decrypt(sender, peer, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope));
        CryptoException.ClaimFailedException second = assertThrows(
                CryptoException.ClaimFailedException.class,
                () -> r.service().decrypt(sender, peer, CryptoTypes.EnvelopeType.PREKEY_INIT, envelope));
        assertEquals(first.getMessage(), second.getMessage());
        // Receiver's own OTPKs are untouched by the failed foreign reference.
        assertTrue(r.keys().oneTimePrivate(100).isPresent());
        assertTrue(r.keys().oneTimePrivate(101).isPresent());
        // No inbound session was created by the rejected envelope.
        assertTrue(r.sessions().loadSession(peer).isEmpty());
        assertEquals(2, foreign.size());
    }
}
