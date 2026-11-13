package com.voidburn.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses one frame (a single JSON object) from the gateway into a typed {@link Frame}.
 *
 * Design notes:
 *  - Every error path returns {@link Frame.Unknown} rather than throwing. The caller
 *    (PluginConnection via VoidburnPlugin's onFrame handler) is a fire-and-forget
 *    pipeline; a frame that cannot be decoded is logged and discarded.
 *  - Gson's instance parser is used for compatibility with Gson 2.8.5, which the
 *    pinned RuneLite 1.13.3 ships. {@code JsonParser.parseString(...)} is 2.8.6+.
 *  - Field-type mismatches (e.g. {@code group} delivered as a string) decode to
 *    Unknown instead of to a half-populated record. This matches the gateway's own
 *    "parser is strict" posture on the inbound side.
 */
final class FrameDecoder {
    private FrameDecoder() {}

    static Frame decode(String json) {
        if (json == null || json.isEmpty()) {
            return new Frame.Unknown("", "");
        }
        JsonObject obj;
        try {
            @SuppressWarnings("deprecation") // Gson 2.8.5 compatibility (JsonParser.parseString is 2.8.6+)
            JsonElement parsed = new JsonParser().parse(json);
            if (parsed == null || !parsed.isJsonObject()) {
                return new Frame.Unknown("", json);
            }
            obj = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            return new Frame.Unknown("", json);
        }

        String type = optString(obj, "type");
        if (type == null) {
            return new Frame.Unknown("", json);
        }
        switch (type) {
            case "widget_open":
                return decodeWidgetOpen(obj, json);
            case "widget_set_root":
                return decodeWidgetSetRoot(obj, json);
            case "widget_open_sub":
                return decodeWidgetOpenSub(obj, json);
            default:
                return new Frame.Unknown(type, json);
        }
    }

    private static Frame decodeWidgetOpen(JsonObject obj, String raw) {
        Integer group = optInt(obj, "group");
        Boolean modal = optBoolean(obj, "modal");
        if (group == null || modal == null) {
            return new Frame.Unknown("widget_open", raw);
        }
        return new Frame.WidgetOpen(group, modal);
    }

    private static Frame decodeWidgetSetRoot(JsonObject obj, String raw) {
        Integer group = optInt(obj, "group");
        if (group == null) {
            return new Frame.Unknown("widget_set_root", raw);
        }
        return new Frame.WidgetSetRoot(group);
    }

    private static Frame decodeWidgetOpenSub(JsonObject obj, String raw) {
        Integer rawTarget = optInt(obj, "rawTarget");
        Integer target = optInt(obj, "target");
        Integer group = optInt(obj, "group");
        Integer interfaceType = optInt(obj, "interfaceType");
        List<Integer> hiddenUids = optIntArray(obj, "hiddenUids");
        if (rawTarget == null || target == null || group == null
            || interfaceType == null || hiddenUids == null) {
            return new Frame.Unknown("widget_open_sub", raw);
        }
        return new Frame.WidgetOpenSub(rawTarget, target, group, interfaceType, hiddenUids);
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        JsonPrimitive p = el.getAsJsonPrimitive();
        return p.isString() ? p.getAsString() : null;
    }

    private static Integer optInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        try {
            // Use longValueExact → int range check: refuses fractional and overflow.
            long v = p.getAsBigDecimal().longValueExact();
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) return null;
            return (int) v;
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Boolean optBoolean(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        JsonPrimitive p = el.getAsJsonPrimitive();
        return p.isBoolean() ? p.getAsBoolean() : null;
    }

    private static List<Integer> optIntArray(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonArray()) return null;
        JsonArray arr = el.getAsJsonArray();
        List<Integer> out = new ArrayList<>(arr.size());
        for (JsonElement item : arr) {
            if (!item.isJsonPrimitive()) return null;
            JsonPrimitive p = item.getAsJsonPrimitive();
            if (!p.isNumber()) return null;
            try {
                long v = p.getAsBigDecimal().longValueExact();
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) return null;
                out.add((int) v);
            } catch (ArithmeticException e) {
                return null;
            }
        }
        return Collections.unmodifiableList(out);
    }
}
