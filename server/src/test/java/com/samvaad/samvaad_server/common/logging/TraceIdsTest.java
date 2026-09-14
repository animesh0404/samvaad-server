package com.samvaad.samvaad_server.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TraceIdsTest {

    @Test
    void acceptsValidCallerSuppliedId() {
        assertEquals("abc-123", TraceIds.resolveOrGenerate("  abc-123  "));
    }

    @Test
    void generatesUuidWhenAbsent() {
        String generated = TraceIds.resolveOrGenerate(null, "   ", null);
        UUID.fromString(generated);
    }

    @Test
    void rejectsUnsafeIds() {
        String injected = "evil\nsecond-line";
        String resolved = TraceIds.resolveOrGenerate(injected);
        assertNotEquals(injected, resolved);
        UUID.fromString(resolved);
    }

    @Test
    void rejectsOverlongIds() {
        String resolved = TraceIds.resolveOrGenerate("x".repeat(129));
        assertTrue(resolved.length() <= 128);
    }
}
