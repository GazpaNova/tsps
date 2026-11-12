package gateway.down

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The JSON encoder is tiny but it sits on the wire between the gateway and the Voidburn
 * plugin. These tests pin each value shape the gateway actually produces plus the
 * escape rules a renderer would trip on if we got them wrong.
 */
class VoidburnPluginJsonTest {
    @Test
    fun `type and empty field map produce a single-key object`() {
        assertEquals("""{"type":"widget_set_root"}""", VoidburnPluginJson.encode("widget_set_root", emptyMap()))
    }

    @Test
    fun `int field becomes a numeric value`() {
        assertEquals(
            """{"type":"widget_set_root","group":30020}""",
            VoidburnPluginJson.encode("widget_set_root", mapOf("group" to 30020)),
        )
    }

    @Test
    fun `boolean field becomes true or false, never 1 or 0`() {
        assertEquals(
            """{"type":"widget_open","modal":true}""",
            VoidburnPluginJson.encode("widget_open", mapOf("modal" to true)),
        )
        assertEquals(
            """{"type":"widget_open","modal":false}""",
            VoidburnPluginJson.encode("widget_open", mapOf("modal" to false)),
        )
    }

    @Test
    fun `null field becomes null`() {
        assertEquals(
            """{"type":"x","v":null}""",
            VoidburnPluginJson.encode("x", mapOf("v" to null)),
        )
    }

    @Test
    fun `list of ints becomes an array`() {
        assertEquals(
            """{"type":"widget_open_sub","hiddenUids":[100,200,300]}""",
            VoidburnPluginJson.encode("widget_open_sub", mapOf("hiddenUids" to listOf(100, 200, 300))),
        )
    }

    @Test
    fun `string escapes the characters a JSON parser rejects`() {
        assertEquals(
            """{"type":"x","s":"a\"b\\c\nd\re\tf"}""",
            VoidburnPluginJson.encode("x", mapOf("s" to "a\"b\\c\nd\re\tf")),
        )
    }

    @Test
    fun `control characters below U+0020 escape as unicode`() {
        assertEquals(
            """{"type":"x","s":"a\u0001b\u001fc"}""",
            VoidburnPluginJson.encode("x", mapOf("s" to "a\u0001b\u001fc")),
        )
    }

    @Test
    fun `fields serialize in map iteration order`() {
        // LinkedHashMap preserves insertion order; this pins that the gateway can send
        // fields in a predictable layout that's easy to log and diff in tests.
        val fields = linkedMapOf<String, Any?>("group" to 30020, "modal" to true, "note" to "hi")
        assertEquals(
            """{"type":"widget_open","group":30020,"modal":true,"note":"hi"}""",
            VoidburnPluginJson.encode("widget_open", fields),
        )
    }

    @Test
    fun `unsupported value type throws instead of silently stringifying`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            VoidburnPluginJson.encode("x", mapOf("v" to java.util.Date()))
        }
        assertEquals("unsupported JSON value type: Date", error.message)
    }

    @Test
    fun `discriminator key is reserved and a payload field named type throws`() {
        // Codex Phase 2a review found that widget_open_sub's numeric `type` field
        // collided with the event discriminator, producing JSON with duplicate keys.
        // The encoder now refuses the collision at the source.
        val error = assertThrows(IllegalArgumentException::class.java) {
            VoidburnPluginJson.encode("widget_open_sub", mapOf("type" to 1, "group" to 30020))
        }
        assertEquals(
            "field name 'type' is reserved as the event discriminator; rename the payload field",
            error.message,
        )
    }

    @Test
    fun `discriminator value is escaped like any other string`() {
        // v1 wrote the discriminator raw; a maliciously-named event would have produced
        // invalid JSON. Current callers use fixed safe names, but the encoder's string
        // contract shouldn't rely on caller discipline.
        assertEquals(
            """{"type":"evil\"name"}""",
            VoidburnPluginJson.encode("evil\"name", emptyMap()),
        )
    }

    @Test
    fun `field names are escaped like string values`() {
        // v1 wrote field names raw; a quote or control character in a field name produced
        // invalid JSON. Fix uses appendString for both positions.
        assertEquals(
            """{"type":"x","weird\"key":42,"ctrl\u0001key":true}""",
            VoidburnPluginJson.encode(
                "x",
                linkedMapOf("weird\"key" to 42, "ctrl\u0001key" to true),
            ),
        )
    }

    @Test
    fun `widget_open_sub shape has no duplicate type key`() {
        // Regression for the exact Codex finding: build the payload the gateway sends
        // for a custom-interface open_sub and assert the output's "type" discriminator
        // survives alongside the numeric interface-type (now named "interfaceType").
        val encoded = VoidburnPluginJson.encode(
            "widget_open_sub",
            linkedMapOf(
                "rawTarget" to 10551312,
                "target" to 10551312,
                "group" to 30020,
                "interfaceType" to 1,
                "hiddenUids" to listOf(1967390731),
            ),
        )
        assertEquals(
            """{"type":"widget_open_sub","rawTarget":10551312,"target":10551312,"group":30020,"interfaceType":1,"hiddenUids":[1967390731]}""",
            encoded,
        )
        // Count occurrences of `"type":` — must be exactly one.
        val typeOccurrences = Regex("\"type\":").findAll(encoded).count()
        assertEquals(1, typeOccurrences, "only one `type` key should appear")
    }

    @Test
    fun `long value fits an int-width field without truncation`() {
        assertEquals(
            """{"type":"x","v":4294967296}""",
            VoidburnPluginJson.encode("x", mapOf("v" to 4_294_967_296L)),
        )
    }
}
