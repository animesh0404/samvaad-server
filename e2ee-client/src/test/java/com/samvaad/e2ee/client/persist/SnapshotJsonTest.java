package com.samvaad.e2ee.client.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The snapshot codec round-trips exactly, and rejects malformed input closed. */
class SnapshotJsonTest {

    @Test
    void roundTripPreservesStringsNumbersAndNesting() {
        SnapshotJson.Obj root = new SnapshotJson.Obj();
        root.put("name", new SnapshotJson.Str("a\"b\\c\nü"));
        root.put("count", new SnapshotJson.Num(-42));
        root.put("nothing", SnapshotJson.NULL);
        SnapshotJson.Arr arr = new SnapshotJson.Arr();
        arr.add(new SnapshotJson.Num(1));
        SnapshotJson.Obj nested = new SnapshotJson.Obj();
        nested.put("empty", new SnapshotJson.Obj());
        arr.add(nested);
        root.put("list", arr);

        SnapshotJson.Obj parsed = SnapshotJson.parseObject(SnapshotJson.render(root));
        assertEquals("a\"b\\c\nü", ((SnapshotJson.Str) parsed.get("name")).value());
        assertEquals(-42, ((SnapshotJson.Num) parsed.get("count")).value());
        assertTrue(parsed.get("nothing") instanceof SnapshotJson.Nul);
        SnapshotJson.Arr parsedArr = (SnapshotJson.Arr) parsed.get("list");
        assertEquals(1, ((SnapshotJson.Num) parsedArr.items().get(0)).value());
    }

    @Test
    void malformedInputFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.parseObject(""));
        assertThrows(IllegalArgumentException.class, () -> SnapshotJson.parseObject("[1,2]"));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotJson.parseObject("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotJson.parseObject("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotJson.parseObject("{\"a\":01}"));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotJson.parseObject("{\"a\":1.5}"));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotJson.parseObject("{\"a\":\"bad\\q\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotJson.parseObject("{\"a\":\"unterminated}"));
        assertThrows(IllegalArgumentException.class,
                () -> SnapshotJson.parseObject("{\"a\":nul}"));
    }
}
