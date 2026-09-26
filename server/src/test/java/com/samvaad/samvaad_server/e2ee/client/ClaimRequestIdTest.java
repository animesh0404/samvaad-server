package com.samvaad.samvaad_server.e2ee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Frozen claimRequestId derivation (PART D):
 * UUID.nameUUIDFromBytes("samvaad-v1-claim:{message}:{sender}:{recipient}")
 * in UTF-8. Only three non-secret UUIDs participate.
 */
class ClaimRequestIdTest {

    private static final UUID MSG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SENDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PEER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** Golden vector guards the exact canonical encoding against drift. */
    private static final UUID GOLDEN = UUID.fromString("a229658a-8e6b-3d3d-ba4d-ffdae6d36768");

    @Test
    void sameInputsGiveSameUuid() {
        assertEquals(CryptoTypes.deriveClaimRequestId(MSG, SENDER, PEER),
                CryptoTypes.deriveClaimRequestId(MSG, SENDER, PEER));
        assertEquals(GOLDEN, CryptoTypes.deriveClaimRequestId(MSG, SENDER, PEER));
    }

    @Test
    void eachInputDimensionChangesTheKey() {
        UUID base = CryptoTypes.deriveClaimRequestId(MSG, SENDER, PEER);
        assertNotEquals(base, CryptoTypes.deriveClaimRequestId(UUID.randomUUID(), SENDER, PEER));
        assertNotEquals(base, CryptoTypes.deriveClaimRequestId(MSG, UUID.randomUUID(), PEER));
        assertNotEquals(base, CryptoTypes.deriveClaimRequestId(MSG, SENDER, UUID.randomUUID()));
        // Sender/recipient positions are not interchangeable.
        assertNotEquals(base, CryptoTypes.deriveClaimRequestId(MSG, PEER, SENDER));
    }

    @Test
    void onlyNonSecretUuidsParticipate() throws Exception {
        Method method = CryptoTypes.class.getMethod("deriveClaimRequestId",
                UUID.class, UUID.class, UUID.class);
        assertEquals(UUID.class, method.getReturnType());
        assertTrue(Modifier.isStatic(method.getModifiers()));
        List<String> params = Arrays.stream(method.getParameters())
                .map(p -> p.getType().getSimpleName()).toList();
        assertEquals(List.of("UUID", "UUID", "UUID"), params);
    }
}
