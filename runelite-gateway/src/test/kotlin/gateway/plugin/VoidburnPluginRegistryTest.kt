package gateway.plugin

import gateway.session.VoidburnPluginChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The registry is the handoff between the Netty WebSocket server and GatewaySession.
 * These tests pin its attach/detach semantics, owner-scoping, concurrency ordering,
 * AND authorization: Phase 2c-1 requires a token submitted at attach to authorize
 * against the session's own mint before the channel is bound.
 */
class VoidburnPluginRegistryTest {
    private class NoopChannel : VoidburnPluginChannel {
        override fun send(message: String) {}
    }

    /** Default registration callbacks: attach just records, authorize accepts the token "T". */
    private fun VoidburnPluginRegistry.registerWithToken(
        username: String,
        token: String = "T",
        attach: (VoidburnPluginChannel?) -> Unit = {},
    ) = register(username, attach, authorize = { it == token })

    @Test
    fun `attach returns null when no session is registered`() {
        val registry = VoidburnPluginRegistry()
        assertNull(registry.attach("Miguel", "T", NoopChannel()))
    }

    @Test
    fun `attach with a correct token delivers the channel`() {
        val registry = VoidburnPluginRegistry()
        var attached: VoidburnPluginChannel? = null
        registry.registerWithToken("Miguel", token = "T") { attached = it }
        val channel = NoopChannel()
        val handle = registry.attach("Miguel", "T", channel)
        assertNotNull(handle)
        assertSame(channel, attached)
    }

    @Test
    fun `attach with a wrong token is rejected and leaves the slot untouched`() {
        val registry = VoidburnPluginRegistry()
        val calls = mutableListOf<VoidburnPluginChannel?>()
        registry.registerWithToken("Miguel", token = "T") { calls += it }
        val handle = registry.attach("Miguel", "wrong", NoopChannel())
        assertNull(handle)
        assertEquals(0, calls.size, "attach callback must not run on failed authorize")
        assertEquals(0, registry.attachmentCount)
    }

    @Test
    fun `attach with a wrong token does not displace a prior valid attach`() {
        val registry = VoidburnPluginRegistry()
        val seen = mutableListOf<VoidburnPluginChannel?>()
        registry.registerWithToken("Miguel", token = "T") { seen += it }
        val chanA = NoopChannel()
        registry.attach("Miguel", "T", chanA)
        assertSame(chanA, seen.last())
        // A hostile second attach with a bogus token must not clear the bound channel.
        val chanB = NoopChannel()
        assertNull(registry.attach("Miguel", "bogus", chanB))
        assertSame(chanA, seen.last(), "existing valid attachment must remain bound")
        assertEquals(1, registry.attachmentCount)
    }

    @Test
    fun `detach delivers null to the registered callback`() {
        val registry = VoidburnPluginRegistry()
        var attached: VoidburnPluginChannel? = NoopChannel()
        registry.registerWithToken("Miguel", token = "T") { attached = it }
        val handle = registry.attach("Miguel", "T", NoopChannel())!!
        registry.detach(handle)
        assertNull(attached)
    }

    @Test
    fun `detach of a stale handle is a no-op and leaves replacement intact`() {
        val registry = VoidburnPluginRegistry()
        val seen = mutableListOf<VoidburnPluginChannel?>()
        registry.registerWithToken("Miguel", token = "T") { seen += it }
        val chanA = NoopChannel()
        val handleA = registry.attach("Miguel", "T", chanA)!!
        val chanB = NoopChannel()
        val handleB = registry.attach("Miguel", "T", chanB)!!
        registry.detach(handleA)
        assertSame(chanB, seen.last())
        assertEquals(1, registry.attachmentCount)
        registry.detach(handleB)
        assertNull(seen.last())
        assertEquals(0, registry.attachmentCount)
    }

    @Test
    fun `unregister of a stale handle is a no-op and leaves replacement intact`() {
        val registry = VoidburnPluginRegistry()
        var secondCalls = 0
        val handleA = registry.registerWithToken("Miguel", token = "T") { }
        registry.registerWithToken("Miguel", token = "T") { secondCalls++ } // B replaces A
        registry.unregister(handleA)
        assertNotNull(registry.attach("Miguel", "T", NoopChannel()))
        assertEquals(1, secondCalls)
        assertEquals(1, registry.registrationCount)
    }

    @Test
    fun `register replacing an existing registration clears the old attachment`() {
        val registry = VoidburnPluginRegistry()
        var aChannel: VoidburnPluginChannel? = null
        val regA = registry.registerWithToken("Miguel", token = "T") { aChannel = it }
        val chanA = NoopChannel()
        registry.attach("Miguel", "T", chanA)
        assertSame(chanA, aChannel)
        var bChannel: VoidburnPluginChannel? = NoopChannel()
        registry.registerWithToken("Miguel", token = "T2") { bChannel = it }
        assertNull(aChannel)
        assertNotNull(bChannel)
        assertNotNull(regA)
        assertEquals(0, registry.attachmentCount)
        // New session has a new token; old token no longer authorizes.
        assertNull(registry.attach("Miguel", "T", NoopChannel()))
        assertNotNull(registry.attach("Miguel", "T2", NoopChannel()))
    }

    @Test
    fun `unregister of a current handle removes the session so future attaches fail`() {
        val registry = VoidburnPluginRegistry()
        val handle = registry.registerWithToken("Miguel", token = "T")
        registry.unregister(handle)
        assertNull(registry.attach("Miguel", "T", NoopChannel()))
    }

    @Test
    fun `unregister clears a held attachment's channel via the null callback`() {
        // Regression (Phase 2c-1 review P3): unregister used to remove the attachment
        // map entry silently while leaving the session's voidburnPluginChannel field
        // populated. Now symmetric with register-replacement: the null callback runs
        // inside the publish lock, so the session's field drops to null as part of
        // the same lifecycle step.
        val registry = VoidburnPluginRegistry()
        val seen = mutableListOf<VoidburnPluginChannel?>()
        val handle = registry.registerWithToken("Miguel", token = "T") { seen += it }
        val channel = NoopChannel()
        registry.attach("Miguel", "T", channel)
        assertSame(channel, seen.last())
        registry.unregister(handle)
        assertNull(seen.last(), "unregister should notify the session with null")
        assertEquals(0, registry.attachmentCount)
    }

    @Test
    fun `stale unregister does not clear a replacement's channel`() {
        // Companion to the P3 fix: the null notification must stay owner-scoped.
        // If session A was replaced by session B and then A's close() fires its
        // unregister, B's channel must stay bound.
        val registry = VoidburnPluginRegistry()
        var aChannel: VoidburnPluginChannel? = null
        val handleA = registry.registerWithToken("Miguel", token = "T") { aChannel = it }
        registry.attach("Miguel", "T", NoopChannel())
        // B replaces A — A's callback is invoked with null (replacement clears the old slot).
        var bChannel: VoidburnPluginChannel? = NoopChannel()
        registry.registerWithToken("Miguel", token = "T2") { bChannel = it }
        assertNull(aChannel)
        // B attaches successfully.
        val bChannelReal = NoopChannel()
        registry.attach("Miguel", "T2", bChannelReal)
        assertSame(bChannelReal, bChannel)
        // Now A's close() fires its stale unregister.
        registry.unregister(handleA)
        // B is still attached; its field is untouched.
        assertSame(bChannelReal, bChannel, "stale unregister must not clear B's channel")
        assertEquals(1, registry.attachmentCount)
    }

    @Test
    fun `registrationCount reflects live registrations`() {
        val registry = VoidburnPluginRegistry()
        assertEquals(0, registry.registrationCount)
        val a = registry.registerWithToken("a")
        registry.registerWithToken("b")
        assertEquals(2, registry.registrationCount)
        registry.unregister(a)
        assertEquals(1, registry.registrationCount)
    }

    @Test
    fun `usernames are case-sensitive`() {
        val registry = VoidburnPluginRegistry()
        registry.registerWithToken("Miguel", token = "T")
        assertNull(registry.attach("miguel", "T", NoopChannel()))
        assertNotNull(registry.attach("Miguel", "T", NoopChannel()))
    }

    @Test
    fun `second attach with the same token replaces the first and old handle becomes stale`() {
        val registry = VoidburnPluginRegistry()
        val seen = mutableListOf<VoidburnPluginChannel?>()
        registry.registerWithToken("Miguel", token = "T") { seen += it }
        val chanA = NoopChannel()
        val handleA = registry.attach("Miguel", "T", chanA)!!
        val chanB = NoopChannel()
        registry.attach("Miguel", "T", chanB)
        assertSame(chanB, seen.last())
        registry.detach(handleA)
        assertSame(chanB, seen.last())
    }

    @Test
    fun `authorize callback runs under the publish lock`() {
        // The docstring states the authorize callback runs under the same lock as the
        // publish steps. We don't test lock ownership directly; the following test
        // (concurrent attaches) exercises the same ordering guarantee end-to-end.
        val registry = VoidburnPluginRegistry()
        val calls = mutableListOf<String>()
        registry.register(
            "Miguel",
            attach = { calls += if (it == null) "detach" else "attach" },
            authorize = { calls += "authorize($it)"; it == "T" },
        )
        registry.attach("Miguel", "T", NoopChannel())
        assertEquals(listOf("authorize(T)", "attach"), calls)
    }

    @Test
    fun `concurrent attaches publish map and callback together, not interleaved`() {
        val registry = VoidburnPluginRegistry()
        val published = AtomicReference<VoidburnPluginChannel?>(null)
        val insideCallback = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        var callbacksSeen = 0
        registry.registerWithToken("Miguel", token = "T") { channel ->
            callbacksSeen++
            if (callbacksSeen == 1) {
                insideCallback.countDown()
                releaseCallback.await(2, TimeUnit.SECONDS)
            }
            published.set(channel)
        }
        val chanA = NoopChannel()
        val chanB = NoopChannel()
        val t1 = Thread { registry.attach("Miguel", "T", chanA) }
        t1.start()
        assertTrue(insideCallback.await(2, TimeUnit.SECONDS), "callback should run")
        val t2 = Thread { registry.attach("Miguel", "T", chanB) }
        t2.start()
        Thread.sleep(50)
        releaseCallback.countDown()
        t1.join(2000)
        t2.join(2000)
        assertSame(chanB, published.get())
        assertEquals(1, registry.attachmentCount)
    }

    @Test
    fun `detach racing attach does not clear the replacement channel`() {
        val registry = VoidburnPluginRegistry()
        val published = AtomicReference<VoidburnPluginChannel?>(NoopChannel())
        val insideCallback = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        var callbacksSeen = 0
        registry.registerWithToken("Miguel", token = "T") { channel ->
            callbacksSeen++
            if (channel == null && callbacksSeen > 1) {
                insideCallback.countDown()
                releaseCallback.await(2, TimeUnit.SECONDS)
            }
            published.set(channel)
        }
        val chanA = NoopChannel()
        val handleA = registry.attach("Miguel", "T", chanA)!!
        val t1 = Thread { registry.detach(handleA) }
        t1.start()
        assertTrue(insideCallback.await(2, TimeUnit.SECONDS), "detach callback should run")
        val chanB = NoopChannel()
        val t2 = Thread { registry.attach("Miguel", "T", chanB) }
        t2.start()
        Thread.sleep(50)
        releaseCallback.countDown()
        t1.join(2000)
        t2.join(2000)
        assertSame(chanB, published.get())
        assertEquals(1, registry.attachmentCount)
    }
}
