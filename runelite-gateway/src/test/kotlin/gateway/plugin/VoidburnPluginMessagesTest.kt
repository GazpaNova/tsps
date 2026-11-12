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
    fun `extra fields are allowed and ignored`() {
        assertEquals(
            "Miguel",
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Miguel","note":"ignored"}"""),
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
        assertEquals(
            "aAb",
            VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"aAb"}"""),
        )
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
    fun `truncated JSON returns null`() {
        assertNull(VoidburnPluginMessages.parseAttach("""{"type":"attach","username":"Mig"""))
    }

    @Test
    fun `field appearing inside a string value is not accepted as the key`() {
        // The payload has "type" INSIDE the note value; the real type field is missing.
        assertNull(VoidburnPluginMessages.parseAttach("""{"note":"mentions \"type\":\"attach\"","username":"X"}"""))
    }
}
