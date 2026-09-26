package com.samvaad.samvaad_server.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Concurrent first messages: both PREKEY_INIT directions stay decryptable. */
class ConcurrentFirstMessageTest {

    @Test
    void bothSidesInitSimultaneouslyAndConverge() {
        UUID aUser = UUID.randomUUID();
        UUID bUser = UUID.randomUUID();
        UUID aDevice = UUID.randomUUID();
        UUID bDevice = UUID.randomUUID();
        CryptoTestFixtures.Harness a = CryptoTestFixtures.harness(aDevice);
        CryptoTestFixtures.Harness b = CryptoTestFixtures.harness(bDevice);

        // Each side knows the other's public bundle seed and holds the private
        // halves of the OTPKs the other side will claim: the server view is
        // stocked from each owner's honestly issued batch.
        a.claimFake().register(bUser, bDevice, 1, "b-phone", 2);
        b.claimFake().register(aUser, aDevice, 1, "a-phone", 1);
        CryptoTestFixtures.uploadOtpks(b, a.claimFake(), bDevice, 100, 5);
        CryptoTestFixtures.uploadOtpks(a, b.claimFake(), aDevice, 200, 5);

        UUID msgA = UUID.randomUUID();
        UUID msgB = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult ra = a.service().sendToDevices(msgA, aDevice,
                "from-a".getBytes(), List.of(CryptoTestFixtures.listed(bUser, bDevice, 1, "b-phone", 2)), Set.of());
        SamvaadCryptoService.FanoutResult rb = b.service().sendToDevices(msgB, bDevice,
                "from-b".getBytes(), List.of(CryptoTestFixtures.listed(aUser, aDevice, 1, "a-phone", 1)), Set.of());
        assertEquals(1, ra.sentCount());
        assertEquals(1, rb.sentCount());

        // Cross-decrypt the PREKEY_INIT envelopes; neither side loses its
        // outbound session — a follow-up message still sends without incident.
        byte[] aEnvelope = a.submitFake().batches().get(0).get(0).envelopeCiphertext();
        byte[] bEnvelope = b.submitFake().batches().get(0).get(0).envelopeCiphertext();
        b.service().decrypt(bDevice, aDevice, CryptoTypes.EnvelopeType.PREKEY_INIT, aEnvelope);
        a.service().decrypt(aDevice, bDevice, CryptoTypes.EnvelopeType.PREKEY_INIT, bEnvelope);

        UUID msgA2 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult ra2 = a.service().sendToDevices(msgA2, aDevice,
                "from-a-2".getBytes(), List.of(CryptoTestFixtures.listed(bUser, bDevice, 1, "b-phone", 2)), Set.of());
        assertEquals(1, ra2.sentCount());
    }
}
