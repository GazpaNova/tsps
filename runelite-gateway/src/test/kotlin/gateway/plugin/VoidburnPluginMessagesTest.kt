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
}
