package com.samvaad.samvaad_server.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Crash-safe retry: COMMITTED envelope bytes are immutable — submit retries
 * resubmit identical bytes with the same claimRequestId, never a new OTPK,
 * never a second session for the same slot.
 */
class CrashRetryTest {

    @Test
    void submitFailureRetriesWithIdenticalBytesAndNoNewClaimOrSession() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);
        List<CryptoTypes.RecipientBundle> directory =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9));

        UUID msg = UUID.randomUUID();
        h.submitFake().failNext();
        SamvaadCryptoService.FanoutResult r1 =
                h.service().sendToDevices(msg, sender, "m".getBytes(), directory, Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.DEFERRED_TRANSIENT, r1.outcomes().get(peer));
        assertEquals(0, h.submitFake().batches().size());

        byte[] committed =
                h.sessions().loadSlot(msg, peer).orElseThrow().envelopeCiphertext();
        int establishes = h.adapter().establishCalls();
        int claims = h.claimFake().calls();

        // Simulated process restart: new service over the SAME durable stores.
        SamvaadCryptoService recovered = new SamvaadCryptoServiceImpl(
                h.adapter(), h.keys(), h.sessions(), h.trust(), h.claimFake(), h.submitFake());
        SamvaadCryptoService.FanoutResult r2 =
                recovered.sendToDevices(msg, sender, "m".getBytes(), directory, Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r2.outcomes().get(peer));
        assertEquals(1, h.submitFake().batches().size());
        assertArrayEquals(committed, h.submitFake().batches().get(0).get(0).envelopeCiphertext());
        // No second OTPK consumed, no second session established for the slot.
        assertEquals(claims, h.claimFake().calls());
        assertEquals(establishes, h.adapter().establishCalls());
        assertTrue(h.sessions().pendingSlots().stream()
                .noneMatch(s -> s.messageRequestId().equals(msg)
                        && s.recipientDeviceId().equals(peer)
                        && s.state() != CryptoTypes.OutboundSlotState.ACKED));
    }

    @Test
    void crashBetweenClaimAndEstablishReusesSameBundle() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);
        UUID msg = UUID.randomUUID();
        UUID claimId = CryptoTypes.deriveClaimRequestId(msg, sender, peer);

        // Manually persist a CLAIMED slot (crash after claim, before establish).
        CryptoTypes.RecipientBundle bundle = h.claimFake().claim(peer, claimId);
        h.sessions().saveSlot(new CryptoTypes.OutboundSlot(msg, sender, peer, claimId,
                CryptoTypes.OutboundSlotState.CLAIMED, null, null, bundle, null));
        int claimsAfterManual = h.claimFake().calls();

        List<CryptoTypes.RecipientBundle> directory =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9));
        SamvaadCryptoService.FanoutResult r =
                h.service().sendToDevices(msg, sender, "m".getBytes(), directory, Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r.outcomes().get(peer));
        // Recovery must NOT issue a fresh claim (which would burn a new OTPK).
        assertEquals(claimsAfterManual, h.claimFake().calls());
        assertTrue(Arrays.equals(bundle.identityPublicKey(), h.sessions().loadSession(peer)
                .orElseThrow().peerIdentityPublicKey()));
    }

    @Test
    void claimRequestIdIsDeterministicPerSlot() {
        UUID msg = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        assertEquals(CryptoTypes.deriveClaimRequestId(msg, sender, peer),
                CryptoTypes.deriveClaimRequestId(msg, sender, peer));
        assertEquals(CryptoTypes.deriveClaimRequestId(msg, sender, peer),
                CryptoTypes.deriveClaimRequestId(
                        UUID.fromString(msg.toString()), UUID.fromString(sender.toString()),
                        UUID.fromString(peer.toString())));
    }
}
