package com.samvaad.e2ee.client.persist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON codec for the client crypto snapshot format (JDK only).
 *
 * <p>Supports exactly what the snapshot needs — objects, arrays, strings,
 * integers, null — and nothing else (no floats, no booleans). Parsing is
 * fail-closed: duplicate keys, trailing content, malformed escapes or
 * numbers, unescaped control characters, and excessive nesting are all
 * rejected. Deliberately dependency-free so the crypto persistence boundary
 * never tracks a JSON library's version or package renames.
 */
final class SnapshotJson {

    sealed interface Val permits Obj, Arr, Str, Num, Nul {
    }

    static final class Obj implements Val {
        private final LinkedHashMap<String, Val> fields = new LinkedHashMap<>();

        void put(String key, Val value) {
            if (fields.containsKey(key)) {
                throw new IllegalArgumentException("duplicate key: " + key);
            }
            fields.put(key, value);
        }

        boolean has(String key) {
            return fields.containsKey(key);
        }

        Val get(String key) {
            Val value = fields.get(key);
            if (value == null) {
                throw new IllegalArgumentException("missing field: " + key);
            }
            return value;
        }

        Map<String, Val> fields() {
            return fields;
        }
    }

    static final class Arr implements Val {
        private final List<Val> items = new ArrayList<>();

        void add(Val value) {
            items.add(value);
        }

        List<Val> items() {
            return items;
        }
    }

    record Str(String value) implements Val {
    }

    record Num(long value) implements Val {
    }

    record Nul() implements Val {
    }

    static final Nul NULL = new Nul();

    private SnapshotJson() {
    }

    static String render(Val value) {
        StringBuilder out = new StringBuilder();
        renderInto(value, out);
        return out.toString();
    }

    private static void renderInto(Val value, StringBuilder out) {
        if (value instanceof Obj obj) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Val> entry : obj.fields().entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                renderString(entry.getKey(), out);
                out.append(':');
                renderInto(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Arr arr) {
            out.append('[');
            boolean first = true;
            for (Val item : arr.items()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                renderInto(item, out);
            }
            out.append(']');
        } else if (value instanceof Str str) {
            renderString(str.value(), out);
        } else if (value instanceof Num num) {
            out.append(num.value());
        } else if (value instanceof Nul) {
            out.append("null");
        } else {
            throw new IllegalArgumentException("unsupported value: " + value);
        }
    }

    private static void renderString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Parses {@code text} and requires the root to be an object. */
    static Obj parseObject(String text) {
        Parser parser = new Parser(text);
        Val root = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("trailing content after JSON root");
        }
        if (!(root instanceof Obj obj)) {
            throw new IllegalArgumentException("JSON root must be an object");
        }
        return obj;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 32;
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        Val parseValue() {
            return parseValue(0);
        }

        private Val parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON nesting too deep");
            }
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> new Str(parseString());
                case 'n' -> {
                    expect("null");
                    yield NULL;
                }
                case '-', '0', '1', '2', '3', '4',
                     '5', '6', '7', '8', '9' -> new Num(parseNumber());
                default -> throw new IllegalArgumentException(
                        "unexpected character at offset " + pos + ": " + c);
            };
        }

        private Obj parseObject(int depth) {
            pos++; // {
            Obj obj = new Obj();
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return obj;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != '"') {
                    throw new IllegalArgumentException("expected string key at offset " + pos);
                }
                String key = parseString();
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != ':') {
                    throw new IllegalArgumentException("expected ':' at offset " + pos);
                }
                pos++;
                obj.put(key, parseValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated object");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return obj;
                } else {
                    throw new IllegalArgumentException(
                            "expected ',' or '}' at offset " + pos);
                }
            }
        }

        private Arr parseArray(int depth) {
            pos++; // [
            Arr arr = new Arr();
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return arr;
            }
            while (true) {
                arr.add(parseValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated array");
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return arr;
                } else {
                    throw new IllegalArgumentException(
                            "expected ',' or ']' at offset " + pos);
                }
            }
        }

        private String parseString() {
            pos++; // opening "
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new IllegalArgumentException("unterminated escape");
                    }
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> out.append(parseHex4());
                        default -> throw new IllegalArgumentException(
                                "invalid escape \\" + e + " at offset " + (pos - 2));
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("unescaped control character in string");
                } else {
                    out.append(c);
                }
            }
        }

        private char parseHex4() {
            if (pos + 4 > text.length()) {
                throw new IllegalArgumentException("truncated unicode escape");
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(pos++), 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("invalid unicode escape");
                }
                code = (code << 4) | digit;
            }
            return (char) code;
        }

        private long parseNumber() {
            int start = pos;
            if (!atEnd() && text.charAt(pos) == '-') {
                pos++;
            }
            if (!atEnd() && text.charAt(pos) == '0') {
                pos++;
                if (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    throw new IllegalArgumentException(
                            "leading zeros not allowed at offset " + start);
                }
                return 0;
            }
            int digits = 0;
            while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
                digits++;
            }
            if (digits == 0) {
                throw new IllegalArgumentException("invalid number at offset " + start);
            }
            if (!atEnd()) {
                char c = text.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E') {
                    throw new IllegalArgumentException(
                            "only integers supported at offset " + start);
                }
            }
            try {
                return Long.parseLong(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("integer out of range at offset " + start, e);
            }
        }

        private void expect(String word) {
            if (!text.startsWith(word, pos)) {
                throw new IllegalArgumentException(
                        "expected '" + word + "' at offset " + pos);
            }
            pos += word.length();
        }
    }
}
