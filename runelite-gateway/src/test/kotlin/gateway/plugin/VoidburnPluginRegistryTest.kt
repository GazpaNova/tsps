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

    @Test
    fun `concurrent attaches publish map and callback together, not interleaved`() {
        // Regression (v2 review): v2 synchronized the map but invoked the callback
        // outside the lock. Attach A could store A, pause, let attach B publish B
        // fully, then wake up and publish A — leaving the map pointing at B but the
        // session's channel field pointing at A.
        //
        // v3 holds map mutation and callback invocation under one monitor. We prove it
        // by pinning one call inside the registry (holding the monitor) while another
        // thread tries to call attach; the second thread must block until the first
        // releases, so both threads' map state and callback state always agree.
        val registry = VoidburnPluginRegistry()
        val published = AtomicReference<VoidburnPluginChannel?>(null)
        val insideCallback = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        // Registration whose callback pauses on the first invocation so we can hold
        // the publish lock while a second thread tries to attach.
        var callbacksSeen = 0
        registry.register("Miguel") { channel ->
            callbacksSeen++
            if (callbacksSeen == 1) {
                insideCallback.countDown()
                // Hold here — this simulates the publish step running while another
                // attach is trying to enter the registry. Under v2, the second
                // attach's map write could occur anyway; under v3, it is blocked by
                // the lock we are implicitly holding.
                releaseCallback.await(2, TimeUnit.SECONDS)
            }
            published.set(channel)
        }
        val chanA = NoopChannel()
        val chanB = NoopChannel()
        // Thread 1: starts attach A, blocks inside the callback above holding the lock.
        val t1 = Thread {
            registry.attach("Miguel", chanA)
        }
        t1.start()
        assertTrue(insideCallback.await(2, TimeUnit.SECONDS), "callback should run")
        // Thread 2: tries to attach B while thread 1 holds the publish lock. It must
        // not succeed in publishing B before thread 1 finishes publishing A.
        val t2 = Thread {
            registry.attach("Miguel", chanB)
        }
        t2.start()
        // Give thread 2 a moment to try. If the registry were not holding its lock
        // across the callback, thread 2 would have published B by now — and when
        // thread 1 wakes it would overwrite with A, leaving the final published
        // channel as A while the map owned B.
        Thread.sleep(50)
        releaseCallback.countDown()
        t1.join(2000)
        t2.join(2000)
        // Serialized publication: the last callback value matches the last map winner.
        // With the lock, thread 2 ran strictly AFTER thread 1, so B is both the map
        // winner and the published channel.
        assertSame(chanB, published.get())
        assertEquals(1, registry.attachmentCount)
    }

    @Test
    fun `detach racing attach does not clear the replacement channel`() {
        // Regression (v2 review): detach A could remove the map entry, then pause
        // before invoking its null callback. Attach B could then publish B. Finally
        // detach A would wake up and publish null, clearing B's channel from the
        // session's view.
        //
        // v3 holds both the map removal and the callback under one lock, so no attach
        // can slide in between. We prove it by pausing the registration callback
        // during detach, then starting an attach from another thread; the attach
        // must block until detach completes, and B ends up as the published value.
        val registry = VoidburnPluginRegistry()
        val published = AtomicReference<VoidburnPluginChannel?>(NoopChannel())
        val insideCallback = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        var callbacksSeen = 0
        registry.register("Miguel") { channel ->
            callbacksSeen++
            if (channel == null && callbacksSeen > 1) {
                // The detach callback (second invocation overall, first null).
                insideCallback.countDown()
                releaseCallback.await(2, TimeUnit.SECONDS)
            }
            published.set(channel)
        }
        val chanA = NoopChannel()
        val handleA = registry.attach("Miguel", chanA)!!
        // Thread 1: detaches A, blocks inside the null callback holding the lock.
        val t1 = Thread { registry.detach(handleA) }
        t1.start()
        assertTrue(insideCallback.await(2, TimeUnit.SECONDS), "detach callback should run")
        // Thread 2: tries to attach B while thread 1 holds the publish lock.
        val chanB = NoopChannel()
        val t2 = Thread { registry.attach("Miguel", chanB) }
        t2.start()
        Thread.sleep(50)
        releaseCallback.countDown()
        t1.join(2000)
        t2.join(2000)
        // After both run, B should be the published channel (not null), proving the
        // detach's null publish ran strictly before attach B's publish of B.
        assertSame(chanB, published.get())
        assertEquals(1, registry.attachmentCount)
    }
}
