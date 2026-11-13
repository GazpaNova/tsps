package com.voidburn.plugin;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses one frame (a single JSON object) from the gateway into a typed {@link Frame}.
 *
 * Strictness contract (matching the gateway's own inbound parser in
 * runelite-gateway/.../gateway/plugin/VoidburnPluginMessages.kt):
 *  - Only strict JSON is accepted. Unquoted keys, single-quoted strings, JS-style
 *    comments, NaN/Infinity, and trailing commas are all rejected.
 *  - Duplicate top-level field names are rejected (no silent "last wins"). Gson's
 *    convenience {@code JsonParser.parse(...)} path transiently enables leniency even
 *    when the caller set it off; this decoder deliberately avoids that path and walks
 *    tokens through {@link JsonReader} with {@code setLenient(false)} so the lenient
 *    bit stays off.
 *  - Trailing content after the closing brace is rejected.
 *  - Field-type mismatches, fractional or out-of-range numbers in integer slots, and
 *    non-number elements in integer arrays all produce {@link Frame.Unknown} rather
 *    than a partially-populated record.
 *  - No path throws out of {@link #decode}; every error returns {@link Frame.Unknown}
 *    carrying the raw JSON for logging.
 */
final class FrameDecoder {
    private FrameDecoder() {}

    /**
     * Opaque holder around a parsed top-level string/number/bool/list value.
     * Non-string scalar types kept so field decoders can type-check without re-walking.
     */
    private static final class Value {
        enum Kind { STRING, NUMBER, BOOL, ARRAY_OF_INT, SKIPPED }
        final Kind kind;
        final String stringValue;
        final BigDecimal numberValue;
        final boolean boolValue;
        final List<Integer> intArray;

        private Value(Kind kind, String s, BigDecimal n, boolean b, List<Integer> arr) {
            this.kind = kind;
            this.stringValue = s;
            this.numberValue = n;
            this.boolValue = b;
            this.intArray = arr;
        }
        static Value string(String s) { return new Value(Kind.STRING, s, null, false, null); }
        static Value number(BigDecimal n) { return new Value(Kind.NUMBER, null, n, false, null); }
        static Value bool(boolean b) { return new Value(Kind.BOOL, null, null, b, null); }
        static Value intArray(List<Integer> a) { return new Value(Kind.ARRAY_OF_INT, null, null, false, a); }
        static Value skipped() { return new Value(Kind.SKIPPED, null, null, false, null); }
    }

    static Frame decode(String json) {
        if (json == null || json.isEmpty()) {
            return new Frame.Unknown("", json == null ? "" : "");
        }
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(false);
        java.util.Map<String, Value> fields;
        try {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                return new Frame.Unknown("", json);
            }
            fields = readObject(reader);
            // Trailing content after the object is rejected. A strict JsonReader only
            // yields END_DOCUMENT when the input truly ended.
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return new Frame.Unknown("", json);
            }
        } catch (MalformedJsonException | IllegalStateException | IOException e) {
            return new Frame.Unknown("", json);
        }
        if (fields == null) {
            return new Frame.Unknown("", json);
        }
        Value typeVal = fields.get("type");
        if (typeVal == null || typeVal.kind != Value.Kind.STRING) {
            return new Frame.Unknown("", json);
        }
        String type = typeVal.stringValue;
        switch (type) {
            case "widget_open":
                return decodeWidgetOpen(fields, json);
            case "widget_set_root":
                return decodeWidgetSetRoot(fields, json);
            case "widget_open_sub":
                return decodeWidgetOpenSub(fields, json);
            default:
                return new Frame.Unknown(type, json);
        }
    }

    /**
     * Read a top-level JSON object from {@code reader}. Returns a map of its top-level
     * fields with duplicate-name detection; nested objects/arrays in non-array positions
     * are skipped structurally and recorded as {@link Value.Kind#SKIPPED}. Returns null
     * if a duplicate key is encountered.
     */
    private static java.util.Map<String, Value> readObject(JsonReader reader) throws IOException {
        reader.beginObject();
        java.util.Map<String, Value> out = new java.util.HashMap<>();
        Set<String> seen = new HashSet<>();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!seen.add(name)) {
                // Duplicate key — reject the whole frame. Drain the rest for cleanliness.
                return null;
            }
            Value v = readValue(reader);
            if (v == null) return null;
            out.put(name, v);
        }
        reader.endObject();
        return out;
    }

    /**
     * Read one JSON value at the current position. Returns a typed Value or Value.skipped
     * for composite values that aren't a recognised array-of-int. Returns null on IO or
     * nested-parse failure.
     */
    private static Value readValue(JsonReader reader) throws IOException {
        JsonToken tok = reader.peek();
        switch (tok) {
            case STRING:
                return Value.string(reader.nextString());
            case NUMBER:
                return Value.number(new BigDecimal(reader.nextString()));
            case BOOLEAN:
                return Value.bool(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return Value.skipped();
            case BEGIN_ARRAY:
                return readArray(reader);
            case BEGIN_OBJECT:
                // Nested object: validate structurally (strictness still applies) but
                // discard the content. We only surface array-of-int and scalar values.
                reader.beginObject();
                while (reader.hasNext()) {
                    reader.nextName();
                    Value nested = readValue(reader);
                    if (nested == null) return null;
                }
                reader.endObject();
                return Value.skipped();
            default:
                return null;
        }
    }

    /**
     * Read a JSON array. If every element is an integer-valued number, returns an
     * {@link Value.Kind#ARRAY_OF_INT}. Any non-integer element causes the array to
     * be treated as a skipped composite — which for the fields we decode (hiddenUids)
     * means the caller will see a type mismatch and return Unknown.
     */
    private static Value readArray(JsonReader reader) throws IOException {
        reader.beginArray();
        List<Integer> ints = new ArrayList<>();
        boolean allInt = true;
        while (reader.hasNext()) {
            JsonToken tok = reader.peek();
            if (allInt && tok == JsonToken.NUMBER) {
                BigDecimal n = new BigDecimal(reader.nextString());
                try {
                    long v = n.longValueExact();
                    if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                        allInt = false;
                    } else {
                        ints.add((int) v);
                    }
                } catch (ArithmeticException e) {
                    allInt = false;
                }
            } else {
                allInt = false;
                // Still consume the element to keep the reader in a valid state.
                Value v = readValue(reader);
                if (v == null) return null;
            }
        }
        reader.endArray();
        return allInt ? Value.intArray(Collections.unmodifiableList(ints)) : Value.skipped();
    }

    private static Frame decodeWidgetOpen(java.util.Map<String, Value> fields, String raw) {
        Integer group = asInt(fields.get("group"));
        Boolean modal = asBool(fields.get("modal"));
        if (group == null || modal == null) {
            return new Frame.Unknown("widget_open", raw);
        }
        return new Frame.WidgetOpen(group, modal);
    }

    private static Frame decodeWidgetSetRoot(java.util.Map<String, Value> fields, String raw) {
        Integer group = asInt(fields.get("group"));
        if (group == null) {
            return new Frame.Unknown("widget_set_root", raw);
        }
        return new Frame.WidgetSetRoot(group);
    }

    private static Frame decodeWidgetOpenSub(java.util.Map<String, Value> fields, String raw) {
        Integer rawTarget = asInt(fields.get("rawTarget"));
        Integer target = asInt(fields.get("target"));
        Integer group = asInt(fields.get("group"));
        Integer interfaceType = asInt(fields.get("interfaceType"));
        List<Integer> hiddenUids = asIntArray(fields.get("hiddenUids"));
        if (rawTarget == null || target == null || group == null
            || interfaceType == null || hiddenUids == null) {
            return new Frame.Unknown("widget_open_sub", raw);
        }
        return new Frame.WidgetOpenSub(rawTarget, target, group, interfaceType, hiddenUids);
    }

    private static Integer asInt(Value v) {
        if (v == null || v.kind != Value.Kind.NUMBER) return null;
        try {
            long n = v.numberValue.longValueExact();
            if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) return null;
            return (int) n;
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Boolean asBool(Value v) {
        if (v == null || v.kind != Value.Kind.BOOL) return null;
        return v.boolValue;
    }

    private static List<Integer> asIntArray(Value v) {
        if (v == null || v.kind != Value.Kind.ARRAY_OF_INT) return null;
        return v.intArray;
    }
}
