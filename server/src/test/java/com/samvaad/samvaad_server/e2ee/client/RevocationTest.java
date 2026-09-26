package com.samvaad.samvaad_server.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Only explicit revocation is terminal. A directory miss retains the session
 * and trust; revocation purges both and skips fan-out.
 */
class RevocationTest {

    @Test
    void transientMissRetainsSessionWhileExplicitRevocationPurges() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID dave = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(dave, peer, 1, "dave-phone", 4);

        UUID msg1 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r1 = h.service().sendToDevices(msg1, sender,
                "hi".getBytes(), List.of(CryptoTestFixtures.listed(dave, peer, 1, "dave-phone", 4)), Set.of());
        assertEquals(1, r1.sentCount());
        assertTrue(h.sessions().loadSession(peer).isPresent());

        // Directory miss, NOT in the revoked set: no purge, no verdict change.
        UUID msg2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r2 =
                h.service().sendToDevices(msg2, sender, "hi2".getBytes(), List.of(), Set.of());
        assertTrue(r2.outcomes().isEmpty());
        assertTrue(h.sessions().loadSession(peer).isPresent());
        assertEquals(CryptoTypes.TrustState.TRUSTED, h.trust().load(peer).orElseThrow().state());

        // Explicit revocation: terminal purge + skip.
        UUID msg3 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r3 = h.service().sendToDevices(msg3, sender,
                "hi3".getBytes(), List.of(CryptoTestFixtures.listed(dave, peer, 1, "dave-phone", 4)),
                Set.of(peer));
        assertEquals(SamvaadCryptoService.DeviceOutcome.SKIPPED_REVOKED, r3.outcomes().get(peer));
        assertTrue(h.sessions().loadSession(peer).isEmpty());
        assertEquals(CryptoTypes.TrustState.REVOKED_EXPLICIT,
                h.trust().load(peer).orElseThrow().state());
    }

    @Test
    void corruptSessionIsQuarantinedNotDeletedAsRevoked() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID erin = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(erin, peer, 1, "erin-phone", 5);

        UUID msg1 = UUID.randomUUID();
        h.service().sendToDevices(msg1, sender, "hi".getBytes(),
                List.of(CryptoTestFixtures.listed(erin, peer, 1, "erin-phone", 5)), Set.of());

        h.adapter().corruptNext();
        UUID msg2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r2 = h.service().sendToDevices(msg2, sender,
                "hi2".getBytes(), List.of(CryptoTestFixtures.listed(erin, peer, 1, "erin-phone", 5)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.FAILED_CORRUPT, r2.outcomes().get(peer));
        // Quarantined as CORRUPT, trust untouched (neither revoked nor paused).
        assertEquals(CryptoTypes.LocalSessionState.CORRUPT,
                h.sessions().loadSession(peer).orElseThrow().state());
        assertEquals(CryptoTypes.TrustState.TRUSTED, h.trust().load(peer).orElseThrow().state());
    }
}
