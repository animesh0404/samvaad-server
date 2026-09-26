package com.samvaad.samvaad_server.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Frozen V1 wire-contract shape (PART B): the envelope has exactly seven
 * fields, the bundle carries address + Kyber triple, and the Signal
 * address model is name=userId + server-assigned integer.
 */
class WireContractTest {

    @Test
    void envelopeHasExactlySevenFrozenFields() {
        List<String> names = Arrays.stream(CryptoTypes.OutboundEnvelope.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toList());
        assertEquals(
                List.of("formatVersion", "cryptoSuite", "envelopeType", "messageRequestId",
                        "senderDeviceId", "recipientDeviceId", "envelopeCiphertext"),
                names);
    }

    @Test
    void envelopeRejectsBadVersionSuiteAndNullType() {
        UUID msg = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        byte[] ct = new byte[]{9};
        assertThrows(IllegalArgumentException.class, () -> new CryptoTypes.OutboundEnvelope(
                999, CryptoTypes.CRYPTO_SUITE, CryptoTypes.EnvelopeType.PREKEY_INIT,
                msg, sender, peer, ct));
        assertThrows(IllegalArgumentException.class, () -> new CryptoTypes.OutboundEnvelope(
                CryptoTypes.ENVELOPE_FORMAT_VERSION, "other-suite",
                CryptoTypes.EnvelopeType.PREKEY_INIT, msg, sender, peer, ct));
        assertThrows(NullPointerException.class, () -> new CryptoTypes.OutboundEnvelope(
                CryptoTypes.ENVELOPE_FORMAT_VERSION, CryptoTypes.CRYPTO_SUITE, null,
                msg, sender, peer, ct));
        CryptoTypes.OutboundEnvelope ok = new CryptoTypes.OutboundEnvelope(
                CryptoTypes.ENVELOPE_FORMAT_VERSION, CryptoTypes.CRYPTO_SUITE,
                CryptoTypes.EnvelopeType.RATCHET, msg, sender, peer, ct);
        assertArrayEquals(ct, ok.envelopeCiphertext());
    }

    @Test
    void bundleCarriesAddressAndKyberTriple() {
        UUID user = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        CryptoTypes.RecipientBundle bundle =
                CryptoTestFixtures.claimed(user, device, 3, "s", 42, 5001);
        assertEquals(user, bundle.userId());
        assertEquals(3, bundle.signalDeviceId());
        assertEquals(new CryptoTypes.SignalAddress(user, 3), bundle.address());
        assertTrue(bundle.hasOneTimePrekey());
        assertTrue(bundle.hasKyber());
        assertNotNull(bundle.kyberPrekey());
        assertNotNull(bundle.kyberPrekeySignature());

        CryptoTypes.RecipientBundle listed =
                CryptoTestFixtures.listed(user, device, 3, "s", 42);
        assertTrue(!listed.hasOneTimePrekey());
        assertTrue(listed.hasKyber());
        assertNull(listed.oneTimePrekey());
    }

    @Test
    void bundleRejectsMissingAddressParts() {
        UUID user = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> CryptoTestFixtures
                .claimed(null, device, 1, "s", 42, 5001));
        assertThrows(IllegalArgumentException.class, () -> CryptoTestFixtures
                .claimed(user, device, 0, "s", 42, 5001));
        assertThrows(IllegalArgumentException.class, () -> CryptoTestFixtures
                .claimed(user, device, -2, "s", 42, 5001));
        assertThrows(NullPointerException.class,
                () -> new CryptoTypes.SignalAddress(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CryptoTypes.SignalAddress(user, 0));
    }
}
