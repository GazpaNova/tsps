package gateway.plugin

import gateway.session.VoidburnPluginChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The registry is the handoff between the Netty WebSocket server and GatewaySession.
 * These tests pin its attach/detach semantics AND its owner-scoping — the properties
 * that make stale cleanup (reconnect races, channel replacement) safe against clobbering
 * the newer entry.
 */
class VoidburnPluginRegistryTest {
    private class NoopChannel : VoidburnPluginChannel {
        override fun send(message: String) {}
    }

    @Test
    fun `attach returns null when no session is registered`() {
        val registry = VoidburnPluginRegistry()
        assertNull(registry.attach("Miguel", NoopChannel()))
    }

    @Test
    fun `attach delivers the channel to the registered callback`() {
        val registry = VoidburnPluginRegistry()
        var attached: VoidburnPluginChannel? = null
        registry.register("Miguel") { channel -> attached = channel }
        val channel = NoopChannel()
        val handle = registry.attach("Miguel", channel)
        assertNotNull(handle)
        assertSame(channel, attached)
    }

    @Test
    fun `detach delivers null to the registered callback`() {
        val registry = VoidburnPluginRegistry()
        var attached: VoidburnPluginChannel? = NoopChannel()
        registry.register("Miguel") { channel -> attached = channel }
        val handle = registry.attach("Miguel", NoopChannel())!!
        registry.detach(handle)
        assertNull(attached)
    }

    @Test
    fun `detach of a stale handle is a no-op and leaves replacement intact`() {
        val registry = VoidburnPluginRegistry()
        val seen = mutableListOf<VoidburnPluginChannel?>()
        registry.register("Miguel") { seen += it }
        val chanA = NoopChannel()
        val handleA = registry.attach("Miguel", chanA)!!
        val chanB = NoopChannel()
        val handleB = registry.attach("Miguel", chanB)!!
        // chanA's handler closes AFTER chanB took over — the stale detach must not
        // clear B's channel from the session's view.
        registry.detach(handleA)
        assertSame(chanB, seen.last())
        assertEquals(1, registry.attachmentCount) // B still attached
        // B detaching now clears the slot as normal.
        registry.detach(handleB)
        assertNull(seen.last())
        assertEquals(0, registry.attachmentCount)
    }

    @Test
    fun `unregister of a stale handle is a no-op and leaves replacement intact`() {
        val registry = VoidburnPluginRegistry()
        var secondCalls = 0
        val handleA = registry.register("Miguel") { }
        registry.register("Miguel") { secondCalls++ } // session B replaces A
        // Session A's close() fires after replacement — must not evict session B.
        registry.unregister(handleA)
        // A fresh attach must still succeed and go to session B.
        assertNotNull(registry.attach("Miguel", NoopChannel()))
        assertEquals(1, secondCalls)
        assertEquals(1, registry.registrationCount)
    }

    @Test
    fun `register replacing an existing registration clears the old attachment`() {
        val registry = VoidburnPluginRegistry()
        var aChannel: VoidburnPluginChannel? = null
        val regA = registry.register("Miguel") { aChannel = it }
        val chanA = NoopChannel()
        registry.attach("Miguel", chanA)
        assertSame(chanA, aChannel)
        // Session B replaces A.
        var bChannel: VoidburnPluginChannel? = NoopChannel()
        registry.register("Miguel") { bChannel = it }
        // The old session saw its channel cleared.
        assertNull(aChannel)
        // And B does not inherit A's channel — its callback has not been invoked.
        assertNotNull(bChannel)
        // Explicitly reference regA to prove the API carries a handle; nothing more to do.
        assertNotNull(regA)
        assertEquals(0, registry.attachmentCount)
    }

    @Test
    fun `unregister of a current handle removes the session so future attaches fail`() {
        val registry = VoidburnPluginRegistry()
        val handle = registry.register("Miguel") {}
        registry.unregister(handle)
        assertNull(registry.attach("Miguel", NoopChannel()))
    }

    @Test
    fun `re-registering replaces the callback target`() {
        val registry = VoidburnPluginRegistry()
        var firstCalls = 0
        var secondCalls = 0
        registry.register("Miguel") { firstCalls++ }
        registry.register("Miguel") { secondCalls++ }
        registry.attach("Miguel", NoopChannel())
        assertEquals(0, firstCalls) // first callback no longer in the slot
        assertEquals(1, secondCalls)
    }

    @Test
    fun `registrationCount reflects live registrations`() {
        val registry = VoidburnPluginRegistry()
        assertEquals(0, registry.registrationCount)
        val a = registry.register("a") {}
        registry.register("b") {}
        assertEquals(2, registry.registrationCount)
        registry.unregister(a)
        assertEquals(1, registry.registrationCount)
    }

    @Test
    fun `usernames are case-sensitive`() {
        val registry = VoidburnPluginRegistry()
        registry.register("Miguel") {}
        assertNull(registry.attach("miguel", NoopChannel()))
        assertNotNull(registry.attach("Miguel", NoopChannel()))
    }

    @Test
    fun `second attach replaces the first and old handle becomes stale`() {
        val registry = VoidburnPluginRegistry()
        val seen = mutableListOf<VoidburnPluginChannel?>()
        registry.register("Miguel") { seen += it }
        val chanA = NoopChannel()
        val handleA = registry.attach("Miguel", chanA)!!
        val chanB = NoopChannel()
        registry.attach("Miguel", chanB)
        assertSame(chanB, seen.last())
        // A's detach now is a no-op (B owns the slot).
        registry.detach(handleA)
        assertSame(chanB, seen.last())
    }
}
