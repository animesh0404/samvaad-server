package com.samvaad.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Address semantics (PART C): Signal address = owning userId + the
 * server-assigned integer. Multi-user fan-out uses UUID routing and
 * integer addressing simultaneously without confusion — including two
 * users that hold the same integer.
 */
class AddressSemanticsTest {

    @Test
    void fanoutUsesUuidRoutingWithIntegerAddressing() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        h.claimFake().register(alice, a1, 1, "alice-phone", 11);
        h.claimFake().register(alice, a2, 2, "alice-laptop", 12);
        h.claimFake().register(bob, b1, 1, "bob-phone", 21);

        List<CryptoTypes.RecipientBundle> directory = List.of(
                CryptoTestFixtures.listed(alice, a1, 1, "alice-phone", 11),
                CryptoTestFixtures.listed(alice, a2, 2, "alice-laptop", 12),
                CryptoTestFixtures.listed(bob, b1, 1, "bob-phone", 21));

        UUID msg = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult result =
                h.service().sendToDevices(msg, sender, "hi all".getBytes(), directory, Set.of());
        assertEquals(3, result.sentCount());

        // Integer addressing observed by the adapter: per-user namespaces,
        // same integer reused across users without collision.
        assertEquals(
                List.of(new CryptoTypes.SignalAddress(alice, 1),
                        new CryptoTypes.SignalAddress(alice, 2),
                        new CryptoTypes.SignalAddress(bob, 1)),
                h.adapter().establishedAddresses());

        // UUID routing observed on the wire envelopes.
        Set<UUID> recipients = h.submitFake().batches().get(0).stream()
                .map(CryptoTypes.OutboundEnvelope::recipientDeviceId)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(a1, a2, b1), recipients);
        for (CryptoTypes.OutboundEnvelope env : h.submitFake().batches().get(0)) {
            assertEquals(sender, env.senderDeviceId());
            assertEquals(msg, env.messageRequestId());
        }

        // Local sessions remain keyed by UUID; trust by UUID too.
        assertTrue(h.sessions().loadSession(a1).isPresent());
        assertTrue(h.sessions().loadSession(a2).isPresent());
        assertTrue(h.sessions().loadSession(b1).isPresent());
        assertTrue(h.trust().load(a1).isPresent());
    }
}
