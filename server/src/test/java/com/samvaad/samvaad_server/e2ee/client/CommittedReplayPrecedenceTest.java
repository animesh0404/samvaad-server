package com.samvaad.samvaad_server.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Once ciphertext is committed for a message/idempotency key, retry replays
 * the exact previously committed envelope — before, and regardless of,
 * current directory/device/trust evaluation. A later identity rotation or
 * revocation never re-encrypts a committed message.
 */
class CommittedReplayPrecedenceTest {

    @Test
    void retryAfterKeyChangeReplaysExactCommittedBytes() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);
        List<CryptoTypes.RecipientBundle> directoryV1 =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9));

        // Commit the message, then fail the submit so the slot stays COMMITTED.
        UUID msg = UUID.randomUUID();
        h.submitFake().failNext();
        SamvaadCryptoService.FanoutResult r1 =
                h.service().sendToDevices(msg, sender, "m".getBytes(), directoryV1, Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.DEFERRED_TRANSIENT, r1.outcomes().get(peer));
        byte[] committed =
                h.sessions().loadSlot(msg, peer).orElseThrow().envelopeCiphertext();
        int claims = h.claimFake().calls();
        int establishes = h.adapter().establishCalls();
        int encrypts = h.adapter().encryptCalls();

        // Recipient rotates their identity AFTER the commit.
        h.claimFake().register(bob, peer, 1, "bob-phone-v2", 9);
        List<CryptoTypes.RecipientBundle> directoryV2 =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone-v2", 9));

        SamvaadCryptoService recovered = new SamvaadCryptoServiceImpl(
                h.adapter(), h.stores(), h.claimFake(), h.submitFake());
        SamvaadCryptoService.FanoutResult r2 =
                recovered.sendToDevices(msg, sender, "m".getBytes(), directoryV2, Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r2.outcomes().get(peer));
        assertEquals(1, h.submitFake().batches().size());
        assertArrayEquals(committed, h.submitFake().batches().get(0).get(0).envelopeCiphertext());
        // No re-evaluation consequences: no new claim, session, or encryption.
        assertEquals(claims, h.claimFake().calls());
        assertEquals(establishes, h.adapter().establishCalls());
        assertEquals(encrypts, h.adapter().encryptCalls());
    }

    @Test
    void retryAfterRevocationStillReplaysCommitForSameMessage() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);
        List<CryptoTypes.RecipientBundle> directory =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9));

        UUID msg = UUID.randomUUID();
        h.submitFake().failNext();
        h.service().sendToDevices(msg, sender, "m".getBytes(), directory, Set.of());
        byte[] committed =
                h.sessions().loadSlot(msg, peer).orElseThrow().envelopeCiphertext();

        // Same-message retry after explicit revocation: the already-committed
        // envelope is still replayed byte-identically (revocation gates new
        // messages, and the idempotent resubmission is arbitrated server-side).
        SamvaadCryptoService recovered = new SamvaadCryptoServiceImpl(
                h.adapter(), h.stores(), h.claimFake(), h.submitFake());
        SamvaadCryptoService.FanoutResult r =
                recovered.sendToDevices(msg, sender, "m".getBytes(), directory, Set.of(peer));
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r.outcomes().get(peer));
        assertArrayEquals(committed, h.submitFake().batches().get(0).get(0).envelopeCiphertext());
    }

    @Test
    void newMessageAfterKeyChangeStillPauses() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);

        UUID msg1 = UUID.randomUUID();
        assertEquals(1, h.service().sendToDevices(msg1, sender, "m".getBytes(),
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9)), Set.of())
                .sentCount());

        // Replay precedence applies per message: a NEW message under the
        // rotated identity is still trust-gated.
        h.claimFake().register(bob, peer, 1, "bob-phone-v2", 9);
        UUID msg2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r2 = h.service().sendToDevices(msg2, sender,
                "m2".getBytes(), List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone-v2", 9)),
                Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.PAUSED_KEY_CHANGED, r2.outcomes().get(peer));
    }
}
