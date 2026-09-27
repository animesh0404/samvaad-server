package com.samvaad.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Envelope-type classification (PART B): first use is PREKEY_INIT,
 * reuse is RATCHET, and decrypt never trial-parses — the wire type
 * selects the path.
 */
class EnvelopeTypeTest {

    @Test
    void firstMessageIsPrekeyInitThenRatchet() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID bob = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(bob, peer, 1, "bob-phone", 9);
        List<CryptoTypes.RecipientBundle> directory =
                List.of(CryptoTestFixtures.listed(bob, peer, 1, "bob-phone", 9));

        UUID msg1 = UUID.randomUUID();
        h.service().sendToDevices(msg1, sender, "one".getBytes(), directory, Set.of());
        CryptoTypes.OutboundEnvelope first = h.submitFake().batches().get(0).get(0);
        assertEquals(CryptoTypes.EnvelopeType.PREKEY_INIT, first.envelopeType());
        assertEquals(CryptoTypes.EnvelopeType.PREKEY_INIT,
                h.sessions().loadSlot(msg1, peer).orElseThrow().envelopeType());

        UUID msg2 = UUID.randomUUID();
        h.service().sendToDevices(msg2, sender, "two".getBytes(), directory, Set.of());
        CryptoTypes.OutboundEnvelope second = h.submitFake().batches().get(1).get(0);
        assertEquals(CryptoTypes.EnvelopeType.RATCHET, second.envelopeType());
    }

    @Test
    void ratchetDecryptWithoutSessionFailsInsteadOfTrialParsing() {
        UUID self = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(self);
        UUID peer = UUID.randomUUID();
        // No session exists: a RATCHET-typed inbound must fail closed rather
        // than fall back to prekey-init parsing.
        assertThrows(CryptoException.SessionCorruptException.class, () -> h.service().decrypt(
                self, peer, CryptoTypes.EnvelopeType.RATCHET, "bytes".getBytes()));
        assertTrue(h.sessions().loadSession(peer).isEmpty()
                || h.sessions().loadSession(peer).orElseThrow().state()
                        == CryptoTypes.LocalSessionState.CORRUPT);
    }

    @Test
    void prekeyDecryptWithoutSessionCreatesInboundSession() {
        UUID self = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(self);
        UUID peer = UUID.randomUUID();
        h.service().decrypt(self, peer, CryptoTypes.EnvelopeType.PREKEY_INIT, "hello".getBytes());
        assertEquals(CryptoTypes.LocalSessionState.READY,
                h.sessions().loadSession(peer).orElseThrow().state());
    }

    @Test
    void committedTypeSurvivesCrashResubmit() {
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
        assertEquals(CryptoTypes.EnvelopeType.PREKEY_INIT,
                h.sessions().loadSlot(msg, peer).orElseThrow().envelopeType());

        SamvaadCryptoService recovered = new SamvaadCryptoServiceImpl(
                h.adapter(), h.stores(), h.claimFake(), h.submitFake());
        recovered.sendToDevices(msg, sender, "m".getBytes(), directory, Set.of());
        assertEquals(CryptoTypes.EnvelopeType.PREKEY_INIT,
                h.submitFake().batches().get(0).get(0).envelopeType());
    }
}
