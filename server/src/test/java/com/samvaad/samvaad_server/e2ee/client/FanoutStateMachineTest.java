package com.samvaad.samvaad_server.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Multi-device fan-out: one session + one envelope per ACTIVE device; reuse after. */
class FanoutStateMachineTest {

    @Test
    void fanoutEstablishesOneSessionPerDeviceAndReusesOnSecondMessage() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID alice = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();
        h.claimFake().register(alice, d1, 1, "alice-phone", 1);
        h.claimFake().register(alice, d2, 2, "alice-laptop", 2);
        h.claimFake().register(alice, d3, 3, "alice-tui", 3);

        List<CryptoTypes.RecipientBundle> directory =
                List.of(CryptoTestFixtures.listed(alice, d1, 1, "alice-phone", 1),
                        CryptoTestFixtures.listed(alice, d2, 2, "alice-laptop", 2),
                        CryptoTestFixtures.listed(alice, d3, 3, "alice-tui", 3));

        UUID msg1 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r1 =
                h.service().sendToDevices(msg1, sender, "hello".getBytes(), directory, Set.of());
        assertEquals(3, r1.sentCount());
        assertEquals(1, h.submitFake().batches().size());
        assertEquals(3, h.submitFake().batches().get(0).size());
        assertEquals(3, h.adapter().establishCalls());
        assertEquals(3, h.adapter().encryptCalls());
        assertEquals(3, h.claimFake().calls());

        // Envelopes are per-device, seven frozen fields, first use is PREKEY_INIT.
        for (CryptoTypes.OutboundEnvelope env : h.submitFake().batches().get(0)) {
            assertEquals(CryptoTypes.ENVELOPE_FORMAT_VERSION, env.formatVersion());
            assertEquals(CryptoTypes.CRYPTO_SUITE, env.cryptoSuite());
            assertEquals(CryptoTypes.EnvelopeType.PREKEY_INIT, env.envelopeType());
            assertEquals(msg1, env.messageRequestId());
        }

        // Second message reuses sessions: no new claims, no new establishments,
        // and the wire type flips to RATCHET.
        UUID msg2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r2 =
                h.service().sendToDevices(msg2, sender, "again".getBytes(), directory, Set.of());
        assertEquals(3, r2.sentCount());
        assertEquals(3, h.adapter().establishCalls());
        assertEquals(6, h.adapter().encryptCalls());
        assertEquals(3, h.claimFake().calls());
        for (CryptoTypes.OutboundEnvelope env : h.submitFake().batches().get(1)) {
            assertEquals(CryptoTypes.EnvelopeType.RATCHET, env.envelopeType());
        }

        // Ciphertext differs per device per message (no shared envelope).
        assertNotEquals(
                h.sessions().loadSlot(msg1, d1).orElseThrow().envelopeCiphertext(),
                h.sessions().loadSlot(msg1, d2).orElseThrow().envelopeCiphertext());
        assertTrue(h.sessions().loadSlot(msg2, d1).orElseThrow().state()
                == CryptoTypes.OutboundSlotState.ACKED);
    }
}
