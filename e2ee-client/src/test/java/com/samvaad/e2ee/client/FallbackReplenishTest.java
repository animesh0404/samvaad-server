package com.samvaad.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.samvaad.e2ee.client.fake.InMemoryStores;

/** OTPK exhaustion falls back to the signed prekey; replenishment is exact batches. */
class FallbackReplenishTest {

    @Test
    void exhaustedPoolSendsWithSignedFallbackMarked() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID frank = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(frank, peer, 1, "frank-phone", 6);
        h.claimFake().setExhausted(peer, true);

        UUID msg = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r = h.service().sendToDevices(msg, sender,
                "hi".getBytes(), List.of(CryptoTestFixtures.listed(frank, peer, 1, "frank-phone", 6)), Set.of());
        assertEquals(1, r.sentCount());
        CryptoTypes.OutboundEnvelope env = h.submitFake().batches().get(0).get(0);
        // First use is PREKEY_INIT on the wire; fallback is local slot/session state.
        assertEquals(CryptoTypes.EnvelopeType.PREKEY_INIT, env.envelopeType());
        assertEquals(CryptoTypes.EstablishmentMode.SIGNED_PREKEY_FALLBACK,
                h.sessions().loadSlot(msg, peer).orElseThrow().establishmentMode());
        assertEquals(CryptoTypes.EstablishmentMode.SIGNED_PREKEY_FALLBACK,
                h.sessions().loadSession(peer).orElseThrow().establishedVia());
    }

    @Test
    void replenishmentContractIsThresholdAndExactBatches() {
        UUID own = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(own);
        InMemoryStores.Prekeys prekeys = new InMemoryStores.Prekeys(h.keys(), h.adapter(), 1);

        assertTrue(prekeys.needsReplenishment(19));
        assertTrue(prekeys.needsReplenishment(0));
        assertTrue(!prekeys.needsReplenishment(20));
        assertTrue(!prekeys.needsReplenishment(100));

        List<SignalAdapter.OneTimePrekeyPair> batch = prekeys.nextBatch();
        assertEquals(100, batch.size());
        assertEquals(100, prekeys.highWaterMark());
        // IDs strictly increase across batches, never reused.
        List<SignalAdapter.OneTimePrekeyPair> batch2 = prekeys.nextBatch();
        assertEquals(200, prekeys.highWaterMark());
        assertTrue(batch2.get(0).prekeyId() > batch.get(batch.size() - 1).prekeyId());
        // Private handles registered for inbound use.
        assertTrue(h.keys().oneTimePrivate(batch.get(0).prekeyId()).isPresent());
    }
}
