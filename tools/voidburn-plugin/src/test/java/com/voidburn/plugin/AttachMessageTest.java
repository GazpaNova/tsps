package com.voidburn.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * The gateway accepts attach messages of a very specific shape; its parser is
 * covered by VoidburnPluginMessagesTest in runelite-gateway. These tests pin
 * the client side's output to exactly that shape, so a future accidental
 * reorder/escape change here can't silently break pairing. We parse the
 * output with Gson to assert the semantic content, plus a few structural
 * asserts to catch things Gson would silently accept (e.g. duplicate keys,
 * trailing garbage) that the gateway rejects.
 */
class AttachMessageTest {

    private JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void canonicalAttachContainsTypeUsernameAndToken() {
        String out = AttachMessage.build("Miguel", "QzNk-WvZr-9sYm-dTaQ");
        JsonObject obj = parse(out);
        assertEquals("attach", obj.get("type").getAsString());
        assertEquals("Miguel", obj.get("username").getAsString());
        assertEquals("QzNk-WvZr-9sYm-dTaQ", obj.get("token").getAsString());
        assertEquals(3, obj.size(), "attach message must carry exactly three fields");
    }

    @Test
    void keyOrderIsStableTypeUsernameToken() {
        // The gateway parser doesn't require a specific order, but we keep it
        // stable so logs are easy to read and test fixtures stay deterministic.
        String out = AttachMessage.build("X", "T");
        assertTrue(out.startsWith("{\"type\":\"attach\","), "type should be the first field");
        assertTrue(
            out.indexOf("\"username\"") < out.indexOf("\"token\""),
            "username should precede token"
        );
    }

    @Test
    void outputHasNoTrailingGarbageAndIsExactlyOneObject() {
        String out = AttachMessage.build("Miguel", "T");
        assertTrue(out.endsWith("}"));
        assertFalse(out.contains("}}"), "message must not look like a nested object");
        assertEquals(1, countTopLevelOpenBraces(out));
    }

    private int countTopLevelOpenBraces(String s) {
        int depth = 0;
        int topLevel = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) { escape = false; continue; }
                if (c == '\\') { escape = true; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') { if (depth == 0) topLevel++; depth++; }
            else if (c == '}') depth--;
        }
        return topLevel;
    }

    @Test
    void doubleQuotesInUsernameAreEscaped() {
        String out = AttachMessage.build("say \"hi\"", "T");
        assertTrue(out.contains("\\\"hi\\\""), "inner quotes should be escaped");
        // Still a valid single object.
        JsonObject obj = parse(out);
        assertEquals("say \"hi\"", obj.get("username").getAsString());
    }

    @Test
    void backslashInUsernameIsEscaped() {
        String out = AttachMessage.build("a\\b", "T");
        assertEquals("a\\b", parse(out).get("username").getAsString());
    }

    @Test
    void controlCharactersInUsernameAreUnicodeEscaped() {
        // The gateway rejects raw control characters below U+0020 in strings.
        // We must escape them client-side.
        String out = AttachMessage.build("Mig\tuel\u0001X", "T");
        assertFalse(out.contains("\t"), "raw tab must not reach the wire");
        assertFalse(out.contains("\u0001"), "raw SOH must not reach the wire");
        // Gson preserves the decoded characters when it reads back.
        assertEquals("Mig\tuel\u0001X", parse(out).get("username").getAsString());
    }

    @Test
    void newlineAndCarriageReturnAreEscaped() {
        String out = AttachMessage.build("line1\nline2\rend", "T");
        assertFalse(out.contains("\n"));
        assertFalse(out.contains("\r"));
        assertEquals("line1\nline2\rend", parse(out).get("username").getAsString());
    }

    @Test
    void tokenIsAlsoEscaped() {
        // Tokens we mint don't contain specials, but the escaper must be
        // applied to both strings symmetrically — anything the user pastes
        // must round-trip.
        String out = AttachMessage.build("Miguel", "tok\"en\\with\ttabs");
        assertEquals("tok\"en\\with\ttabs", parse(out).get("token").getAsString());
    }

    @Test
    void emptyStringsStillProduceStructurallyValidJson() {
        // The gateway rejects empty username/token semantically — this just
        // proves the client doesn't produce malformed JSON for them.
        String out = AttachMessage.build("", "");
        JsonObject obj = parse(out);
        assertEquals("", obj.get("username").getAsString());
        assertEquals("", obj.get("token").getAsString());
    }

    @Test
    void unicodeOutsideBmpSurvivesRoundTrip() {
        // Not expected in usernames, but the escaper shouldn't corrupt
        // multi-char sequences either.
        String name = "😀Miguel";
        String out = AttachMessage.build(name, "T");
        assertEquals(name, parse(out).get("username").getAsString());
    }
}
