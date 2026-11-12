package gateway.plugin

import gateway.session.VoidburnPluginChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The registry is the handoff between the Netty WebSocket server and GatewaySession.
 * These tests pin its attach/detach semantics and concurrency expectations.
 */
class VoidburnPluginRegistryTest {
    private class NoopChannel : VoidburnPluginChannel {
        override fun send(message: String) {}
    }

    @Test
    fun `attach returns false when no session is registered`() {
        val registry = VoidburnPluginRegistry()
        assertFalse(registry.attach("Miguel", NoopChannel()))
    }

    @Test
    fun `attach delivers the channel to the registered callback`() {
        val registry = VoidburnPluginRegistry()
        var attached: VoidburnPluginChannel? = null
        registry.register("Miguel") { channel -> attached = channel }
        val channel = NoopChannel()
        assertTrue(registry.attach("Miguel", channel))
        assertSame(channel, attached)
    }

    @Test
    fun `detach delivers null to the registered callback`() {
        val registry = VoidburnPluginRegistry()
        var attached: VoidburnPluginChannel? = NoopChannel()
        registry.register("Miguel") { channel -> attached = channel }
        registry.detach("Miguel")
        assertNull(attached)
    }

    @Test
    fun `detach for an unknown username is a no-op`() {
        val registry = VoidburnPluginRegistry()
        registry.detach("nobody") // must not throw
    }

    @Test
    fun `unregister removes the session so future attaches fail`() {
        val registry = VoidburnPluginRegistry()
        registry.register("Miguel") {}
        registry.unregister("Miguel")
        assertFalse(registry.attach("Miguel", NoopChannel()))
    }

    @Test
    fun `re-registering a username replaces the previous callback`() {
        val registry = VoidburnPluginRegistry()
        var firstCalls = 0
        var secondCalls = 0
        registry.register("Miguel") { firstCalls++ }
        registry.register("Miguel") { secondCalls++ }
        registry.attach("Miguel", NoopChannel())
        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun `size reflects registered sessions`() {
        val registry = VoidburnPluginRegistry()
        assertEquals(0, registry.size)
        registry.register("a") {}
        registry.register("b") {}
        assertEquals(2, registry.size)
        registry.unregister("a")
        assertEquals(1, registry.size)
    }

    @Test
    fun `usernames are case-sensitive`() {
        val registry = VoidburnPluginRegistry()
        registry.register("Miguel") {}
        assertFalse(registry.attach("miguel", NoopChannel()))
        assertTrue(registry.attach("Miguel", NoopChannel()))
    }
}
