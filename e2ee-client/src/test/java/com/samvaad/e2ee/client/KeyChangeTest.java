package com.samvaad.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Key-change pauses encrypt, with explicit accept/reject paths. */
class KeyChangeTest {

    @Test
    void changedIdentityPausesThenAcceptResumesAndRejectStaysPaused() {
        UUID sender = UUID.randomUUID();
        CryptoTestFixtures.Harness h = CryptoTestFixtures.harness(sender);
        UUID carol = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        h.claimFake().register(carol, peer, 1, "carol-v1", 3);

        UUID msg1 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r1 = h.service().sendToDevices(msg1, sender,
                "hi".getBytes(), List.of(CryptoTestFixtures.listed(carol, peer, 1, "carol-v1", 3)), Set.of());
        assertEquals(1, r1.sentCount());
        int establishes = h.adapter().establishCalls();

        // Same deviceId, rotated identity key -> paused, no new session.
        UUID msg2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r2 = h.service().sendToDevices(msg2, sender,
                "hi2".getBytes(), List.of(CryptoTestFixtures.listed(carol, peer, 1, "carol-v2", 3)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.PAUSED_KEY_CHANGED, r2.outcomes().get(peer));
        assertEquals(establishes, h.adapter().establishCalls());
        assertEquals(CryptoTypes.TrustState.PAUSED_KEY_CHANGED,
                h.trust().load(peer).orElseThrow().state());

        // Reject keeps the pause.
        h.service().rejectKeyChange(peer);
        UUID msg3 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r3 = h.service().sendToDevices(msg3, sender,
                "hi3".getBytes(), List.of(CryptoTestFixtures.listed(carol, peer, 1, "carol-v2", 3)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.PAUSED_KEY_CHANGED, r3.outcomes().get(peer));

        // Accept purges the old session and re-establishes on next send.
        // Acceptance takes the canonical identity key bytes, not a display string.
        byte[] newKey = CryptoTestFixtures.key("carol-v2:id");
        h.claimFake().register(carol, peer, 1, "carol-v2", 3);
        h.service().acceptKeyChange(peer, newKey);
        assertTrue(h.sessions().loadSession(peer).isEmpty());
        UUID msg4 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r4 = h.service().sendToDevices(msg4, sender,
                "hi4".getBytes(), List.of(CryptoTestFixtures.listed(carol, peer, 1, "carol-v2", 3)), Set.of());
        assertEquals(1, r4.sentCount());
        assertEquals(CryptoTypes.TrustState.TRUSTED, h.trust().load(peer).orElseThrow().state());
    }
}
