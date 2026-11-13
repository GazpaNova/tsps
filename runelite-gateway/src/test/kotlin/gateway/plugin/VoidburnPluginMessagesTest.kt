package gateway.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The attach message parser is the gateway's first point of contact with external
 * input on the plugin WebSocket. These tests pin its accept-set tightly: anything the
 * parser mis-decodes becomes an attempt to bind a session with content that didn't
 * come from the player who owns it, so the shape rules must be strict and well-
 * covered. Token semantic validation (comparing against the session's mint) happens
 * elsewhere — this parser only enforces shape.
 */
class VoidburnPluginMessagesTest {
    /** Reusable token literal for the valid-shape tests. */
    private val T = "token-value-T"

    private fun attach(username: String = "Miguel", token: String = T) =
        VoidburnPluginMessages.Attach(username, token)

    @Test
    fun `canonical attach message returns the username and token`() {
        assertEquals(
            attach(),
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":"$T"}"""),
        )
    }

    @Test
    fun `whitespace around keys and values is tolerated`() {
        assertEquals(
            attach(),
            VoidburnPluginMessages.parseAttach("""{  "type" : "attach" , "username" : "Miguel" , "token" : "$T"  }"""),
        )
    }

    @Test
    fun `extra string fields are allowed and ignored`() {
        assertEquals(
            attach(),
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":"$T","note":"ignored"}"""),
        )
    }

    @Test
    fun `extra non-string fields are skipped structurally`() {
        assertEquals(
            attach(),
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":"$T","count":42,"flags":[1,2,3],"meta":{"k":"v"},"ok":true,"err":null}"""),
        )
    }

    @Test
    fun `other types return null`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"click","componentId":186}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"heartbeat"}"""))
    }

    @Test
    fun `missing type returns null`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"username":"Miguel","token":"$T"}"""))
    }

    @Test
    fun `missing username returns null even with correct type`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","token":"$T"}"""))
    }

    @Test
    fun `missing token returns null even with username`() {
        // Regression (Phase 2c-1): token is now required. A v2-shaped attach message
        // without a token must not authorize any session.
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel"}"""))
    }

    @Test
    fun `empty token is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":""}"""))
    }

    @Test
    fun `overlong token is rejected`() {
        val overlong = "a".repeat(129)
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":"$overlong"}"""))
    }

    @Test
    fun `non-string token is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":42}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":null}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":true}"""))
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
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Alice","token":"$T""""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Alice","token":"$T"""))
    }

    @Test
    fun `nested object with target fields is rejected`() {
        assertNull(
            VoidburnPluginMessages.parseAttach("""{"nested":{"type":"attach","username":"Alice","token":"$T"}}"""),
        )
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":{"type":"attach"},"username":"Alice","token":"$T"}"""))
    }

    @Test
    fun `trailing garbage after the closing brace is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":"$T"}trailing"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":"$T"} {"type":"other"}"""))
    }

    @Test
    fun `duplicate keys are rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","type":"other","username":"X","token":"$T"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"username":"A","type":"attach","username":"B","token":"$T"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":"A","token":"B"}"""))
    }

    @Test
    fun `duplicate key is rejected even when the first value was non-string`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":null,"type":"attach","username":"Alice","token":"$T"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"username":42,"username":"B","type":"attach","token":"$T"}"""))
    }

    @Test
    fun `username escapes are decoded`() {
        assertEquals(
            VoidburnPluginMessages.Attach("say \"hi\"\n", T),
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"say \"hi\"\n","token":"$T"}"""),
        )
    }

    @Test
    fun `username unicode escape is decoded`() {
        assertEquals(
            attach(username = "aAb"),
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"aAb","token":"$T"}"""),
        )
    }

    @Test
    fun `malformed unicode escape returns null without throwing`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\uGGGG","token":"$T"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\u00","token":"$T"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\u12","token":"$T"}"""))
    }

    @Test
    fun `unicode escape with a sign is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\u+041","token":"$T"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\u-041","token":"$T"}"""))
    }

    @Test
    fun `unknown escape returns null`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"\q","token":"$T"}"""))
    }

    @Test
    fun `raw control characters below U+0020 in strings are rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("{\"type\":\"attach\",\"username\":\"Mig\tuel\",\"token\":\"$T\"}"))
        assertNull(VoidburnPluginMessages.parseAttach("{\"type\":\"attach\",\"username\":\"Mig\u000Buel\",\"token\":\"$T\"}"))
        assertNull(VoidburnPluginMessages.parseAttach("{\"type\":\"attach\",\"username\":\"Mig\u0001uel\",\"token\":\"$T\"}"))
        // Also for a control character inside the token value.
        assertNull(VoidburnPluginMessages.parseAttach("{\"type\":\"attach\",\"username\":\"Miguel\",\"token\":\"to\tken\"}"))
    }

    @Test
    fun `empty username is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"","token":"$T"}"""))
    }

    @Test
    fun `overlong username is rejected`() {
        val overlong = "a".repeat(33)
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"$overlong","token":"$T"}"""))
    }

    @Test
    fun `truncated string literal returns null`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Mig"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","token":"tok"""))
    }

    @Test
    fun `field appearing inside a string value is not accepted as the key`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"note":"mentions \"type\":\"attach\"","username":"X","token":"$T"}"""))
    }

    @Test
    fun `missing colon is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type" "attach","username":"Miguel","token":"$T"}"""))
    }

    @Test
    fun `missing comma between fields is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach" "username":"Miguel","token":"$T"}"""))
    }

    @Test
    fun `empty object is rejected for missing type`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{}"""))
    }

    @Test
    fun `username value that is not a string is rejected`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":42,"token":"$T"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":null,"token":"$T"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":true,"token":"$T"}"""))
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":{"nested":"x"},"token":"$T"}"""))
    }

    @Test
    fun `excessive nesting is rejected without stack overflow`() {
        val deepArray = "[".repeat(12_000) + "1" + "]".repeat(12_000)
        val payload = """{"type":"attach","username":"Miguel","token":"$T","extra":$deepArray}"""
        assertNull(VoidburnPluginMessages.parseAttach(payload))
        val shallow = """{"type":"attach","username":"Miguel","token":"$T","extra":[[[[1]]]]}"""
        assertEquals(attach(), VoidburnPluginMessages.parseAttach(shallow))
    }

    // --- Phase 2d-1 click parsing ---

    @Test
    fun `canonical click carries widgetId sub obj and op`() {
        val uid = (30020 shl 16) or 186
        assertEquals(
            VoidburnPluginMessages.Click(uid, 0xFFFF, 0xFFFF, 1),
            VoidburnPluginMessages.parseClick(
                """{"type":"click","widgetId":$uid,"sub":65535,"obj":65535,"op":1}"""
            ),
        )
    }

    @Test
    fun `click sub and obj default to NONE when omitted`() {
        val uid = (30020 shl 16) or 186
        assertEquals(
            VoidburnPluginMessages.Click(uid, 0xFFFF, 0xFFFF, 1),
            VoidburnPluginMessages.parseClick(
                """{"type":"click","widgetId":$uid,"op":1}"""
            ),
        )
    }

    @Test
    fun `click wrong type discriminator rejects`() {
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"attach","widgetId":1966266,"op":1}"""
        ))
    }

    @Test
    fun `click missing required fields rejects`() {
        // No widgetId
        assertNull(VoidburnPluginMessages.parseClick("""{"type":"click","op":1}"""))
        // No op
        assertNull(VoidburnPluginMessages.parseClick("""{"type":"click","widgetId":1966266}"""))
    }

    @Test
    fun `click non-integer values reject`() {
        assertNull(VoidburnPluginMessages.parseClick("""{"type":"click","widgetId":"1966266","op":1}"""))
        assertNull(VoidburnPluginMessages.parseClick("""{"type":"click","widgetId":1966266,"op":"1"}"""))
        assertNull(VoidburnPluginMessages.parseClick("""{"type":"click","widgetId":true,"op":1}"""))
    }

    @Test
    fun `click fractional widget id rejects`() {
        assertNull(VoidburnPluginMessages.parseClick("""{"type":"click","widgetId":1.5,"op":1}"""))
    }

    @Test
    fun `click widget id overflowing int rejects`() {
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"click","widgetId":99999999999,"op":1}"""
        ))
    }

    @Test
    fun `click sub and obj out of u16 range reject`() {
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"click","widgetId":1966266,"sub":70000,"op":1}"""
        ))
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"click","widgetId":1966266,"sub":-1,"op":1}"""
        ))
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"click","widgetId":1966266,"obj":70000,"op":1}"""
        ))
    }

    @Test
    fun `click op out of range rejects`() {
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"click","widgetId":1966266,"op":0}"""
        ))
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"click","widgetId":1966266,"op":11}"""
        ))
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"click","widgetId":1966266,"op":-1}"""
        ))
    }

    @Test
    fun `click duplicate key rejects`() {
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"click","widgetId":1966266,"widgetId":1,"op":1}"""
        ))
    }

    @Test
    fun `parseClick refuses an attach shape`() {
        // Sanity: parseAttach and parseClick don't cross-accept.
        assertNull(VoidburnPluginMessages.parseClick(
            """{"type":"attach","username":"Miguel","token":"$T"}"""
        ))
    }

    @Test
    fun `parseAttach refuses a click shape`() {
        assertNull(VoidburnPluginMessages.parseAttach(
            """{"type":"click","widgetId":1,"op":1}"""
        ))
    }

    @Test
    fun `click accepts large positive widget id`() {
        // Full 32-bit signed int: a packed uid with group near 32767 has a huge
        // positive widgetId. Group 32767, component 1 = (32767 << 16) | 1 = 2147418113.
        val uid = (32767 shl 16) or 1
        assertEquals(
            VoidburnPluginMessages.Click(uid, 0xFFFF, 0xFFFF, 1),
            VoidburnPluginMessages.parseClick(
                """{"type":"click","widgetId":$uid,"op":1}"""
            ),
        )
    }

    @Test
    fun `click optional sub present but wrong type rejects`() {
        // Regression (v1 P2-a): null / bool / string / object / array in an
        // optional field must NOT default silently to NONE — they're present-
        // but-wrong-typed, distinct from "sub omitted."
        val uid = 1966266
        for (bad in listOf("null", "true", "false", "\"65535\"", "[1,2]", "{}")) {
            assertNull(
                VoidburnPluginMessages.parseClick(
                    """{"type":"click","widgetId":$uid,"op":1,"sub":$bad}"""
                ),
                "sub:$bad should reject (not default to NONE)",
            )
            assertNull(
                VoidburnPluginMessages.parseClick(
                    """{"type":"click","widgetId":$uid,"op":1,"obj":$bad}"""
                ),
                "obj:$bad should reject",
            )
        }
    }

    @Test
    fun `attach tolerates unknown non-integer numeric fields`() {
        // Regression (v1 P2-b): an unknown top-level field with a JSON number
        // that can't parse as Long (fractional, scientific, out-of-range) used
        // to reject the whole message because the parser forced Long-parse on
        // every number. Now numbers are stored as captured text and parsed to
        // int only at the consumer. parseAttach doesn't care about these.
        assertEquals(
            attach(),
            VoidburnPluginMessages.parseAttach(
                """{"type":"attach","username":"Miguel","token":"$T","future":1.5}"""
            ),
        )
        assertEquals(
            attach(),
            VoidburnPluginMessages.parseAttach(
                """{"type":"attach","username":"Miguel","token":"$T","future":1e2}"""
            ),
        )
        assertEquals(
            attach(),
            VoidburnPluginMessages.parseAttach(
                """{"type":"attach","username":"Miguel","token":"$T","future":9223372036854775808}"""
            ),
        )
    }

    @Test
    fun `click accepts negative widget id for high-bit-set group`() {
        // Group 32768..65535 has the sign bit set, so the packed uid is
        // negative when read as a signed int. The parser must accept this.
        // Group 40000, component 1 = (40000 << 16) | 1 = -1638399999 (signed).
        val uid = (40000 shl 16) or 1
        assertEquals(
            VoidburnPluginMessages.Click(uid, 0xFFFF, 0xFFFF, 1),
            VoidburnPluginMessages.parseClick(
                """{"type":"click","widgetId":$uid,"op":1}"""
            ),
        )
    }
}
