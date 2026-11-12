package gateway.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The attach message parser is the gateway's first point of contact with external input
 * on the plugin WebSocket. These tests pin its accept-set tightly: anything the parser
 * mis-decodes becomes a session binding that didn't come from the player who owns the
 * session, so the shape rules must be strict and well-covered.
 */
class VoidburnPluginMessagesTest {
    @Test
    fun `canonical attach message returns the username`() {
        assertEquals("Miguel", VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel"}"""))
    }

    @Test
    fun `whitespace around keys and values is tolerated`() {
        assertEquals(
            "Miguel",
            VoidburnPluginMessages.parseAttach("""{  "type" : "attach" , "username" : "Miguel"  }"""),
        )
    }

    @Test
    fun `extra string fields are allowed and ignored`() {
        assertEquals(
            "Miguel",
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","note":"ignored"}"""),
        )
    }

    @Test
    fun `extra non-string fields are skipped structurally`() {
        // The parser validates these values' structure but doesn't surface them.
        assertEquals(
            "Miguel",
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","count":42,"flags":[1,2,3],"meta":{"k":"v"},"ok":true,"err":null}"""),
        )
    }

    @Test
    fun `other types return null`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"click","componentId":186}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"heartbeat"}"""))
    }

    @Test
    fun `missing type returns null`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"username":"Miguel"}"""))
    }

    @Test
    fun `missing username returns null even with correct type`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach"}"""))
    }

    @Test
    fun `non-object payloads return null`() {
        assertNull(VoidburnPluginMessages.parseAttach(""""attach""""))
        assertNull(VoidburnPluginMessages.parseAttach("""null"""))
        assertNull(VoidburnPluginMessages.parseAttach(""""""))
        assertNull(VoidburnPluginMessages.parseAttach("""[]"""))
        assertNull(VoidburnPluginMessages.parseAttach("""42"""))
    }

    @Test
    fun `missing closing brace is rejected`() {
        // Regression: v1 accepted `{"type":"attach","username":"Alice"` — a loose
        // substring scanner found the username before realizing the object never closed.
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Alice""""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Alice"""))
    }

    @Test
    fun `nested object with target fields is rejected`() {
        // Regression: v1 accepted `{"nested":{"type":"attach","username":"Alice"}}` —
        // substring scanner thought "type" appeared as a top-level key.
        assertNull(
            VoidburnPluginMessages.parseAttach("""{"nested":{"type":"attach","username":"Alice"}}"""),
        )
        // Also when the nested object is in the "type" value's position.
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":{"type":"attach"},"username":"Alice"}"""))
    }

    @Test
    fun `trailing garbage after the closing brace is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel"}trailing"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel"} {"type":"other"}"""))
    }

    @Test
    fun `duplicate keys are rejected`() {
        // Different JSON stacks disagree on duplicate handling — reject outright so the
        // attach shape is unambiguous.
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","type":"other","username":"X"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"username":"A","type":"attach","username":"B"}"""))
    }

    @Test
    fun `duplicate key is rejected even when the first value was non-string`() {
        // Regression (v2 review): v2's duplicate check compared against the string-only
        // output map. `"type":null` never landed there, so a later `"type":"attach"`
        // slipped past the duplicate check and the parser returned the username.
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":null,"type":"attach","username":"Alice"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"username":42,"username":"B","type":"attach"}"""))
    }

    @Test
    fun `username escapes are decoded`() {
        assertEquals(
            "say \"hi\"\n",
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"say \"hi\"\n"}"""),
        )
    }

    @Test
    fun `username unicode escape is decoded`() {
        // "A" is 'A'.
        assertEquals(
            "aAb",
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"aAb"}"""),
        )
    }

    @Test
    fun `malformed unicode escape returns null without throwing`() {
        // Regression: v1 threw NumberFormatException on `\uGGGG`. Must return null safely.
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\uGGGG"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\u00"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\u12"}"""))
    }

    @Test
    fun `unicode escape with a sign is rejected`() {
        // Regression (v2 review): v2 used toIntOrNull(16) which accepts a leading sign,
        // so `\u+041` decoded to A. Each of the four digits must be hex.
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\u+041"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\u-041"}"""))
    }

    @Test
    fun `raw control characters below U+0020 in strings are rejected`() {
        // Regression (v2 review): v2 only rejected literal LF/CR. JSON forbids every
        // unescaped character below U+0020 — tab, vertical tab, form feed, etc.
        assertNull(VoidburnPluginMessages.parseAttach("{\"type\":\"attach\",\"username\":\"Mig\tuel\"}"))
        assertNull(VoidburnPluginMessages.parseAttach("{\"type\":\"attach\",\"username\":\"Mig\u000Buel\"}"))
        assertNull(VoidburnPluginMessages.parseAttach("{\"type\":\"attach\",\"username\":\"Mig\u0001uel\"}"))
    }

    @Test
    fun `excessive nesting is rejected without stack overflow`() {
        // Regression (v2 review): skipObject/skipArray recursed without bound; 12000
        // nested arrays in a single attach fit under the 64 KiB frame limit and blew
        // the Netty thread's stack. Depth capped at 16 now.
        val deepArray = "[".repeat(12_000) + "1" + "]".repeat(12_000)
        val payload = """{"type":"attach","username":"Miguel","extra":$deepArray}"""
        assertNull(VoidburnPluginMessages.parseAttach(payload))
        // Shallow nesting still parses — the limit is depth, not length.
        val shallow = """{"type":"attach","username":"Miguel","extra":[[[[1]]]]}"""
        assertEquals("Miguel", VoidburnPluginMessages.parseAttach(shallow))
    }

    @Test
    fun `unknown escape returns null`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\q"}"""))
    }

    @Test
    fun `raw control characters in strings are rejected`() {
        // Literal newline inside the quoted string (not escaped).
        val withNewline = "{\"type\":\"attach\",\"username\":\"Mig\nuel\"}"
        assertNull(VoidburnPluginMessages.parseAttach(withNewline))
    }

    @Test
    fun `empty username is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":""}"""))
    }

    @Test
    fun `overlong username is rejected`() {
        val overlong = "a".repeat(33)
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"$overlong"}"""))
    }

    @Test
    fun `truncated string literal returns null`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Mig"""))
    }

    @Test
    fun `field appearing inside a string value is not accepted as the key`() {
        // The payload has "type" INSIDE the note value; the real type field is missing.
        assertNull(VoidburnPluginMessages.parseAttach("""{"note":"mentions \"type\":\"attach\"","username":"X"}"""))
    }

    @Test
    fun `missing colon is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type" "attach","username":"Miguel"}"""))
    }

    @Test
    fun `missing comma between fields is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach" "username":"Miguel"}"""))
    }

    @Test
    fun `empty object is rejected for missing type`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{}"""))
    }

    @Test
    fun `username value that is not a string is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":42}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":null}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":true}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":{"nested":"x"}}"""))
    }
}
