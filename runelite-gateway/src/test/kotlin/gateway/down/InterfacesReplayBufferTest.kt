package gateway.down

import gateway.cache.Cache
import gateway.cache.EnumTypes
import gateway.cache.VarTypes
import gateway.config.GatewayConfig
import gateway.session.InterfaceTree
import gateway.session.VarpMirror
import gateway.session.VoidburnPluginChannel
import gateway.tsps.TspsPacket
import gateway.tsps.TspsServerPacket
import gateway.tsps.TspsWriter
import net.rsprot.protocol.message.OutgoingGameMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pre-attach replay buffer: packets sent before a plugin attaches are
 * held FIFO and drained to the channel when it becomes available.
 *
 * The buffer sits inside Interfaces; this suite drives it through the
 * public packet-handling path (TspsServerPacket.WIDGET_SET_TEXT /
 * WIDGET_SET_HIDDEN for custom groups) and through a controllable
 * channel supplier so attach/detach timing is explicit. No mocked
 * synchronization is needed because Interfaces is session-thread only
 * and JUnit runs the whole test on one thread.
 */
class InterfacesReplayBufferTest {
    private val cache = Cache.open(GatewayConfig.load().cacheDirectory)
    private val enums = EnumTypes(cache)
    private val sent = ArrayList<OutgoingGameMessage>()
    private val forwarded = ArrayList<String>()
    private var channelAvailable = false
    private val channel = object : VoidburnPluginChannel {
        override fun send(message: String) { forwarded += message }
    }
    private val interfaces = Interfaces(
        VarpMirror(VarTypes(cache)),
        InterfaceTree(enums),
        StockMarket { sent += it },
        {},
        { true },
        { sent += it },
        { if (channelAvailable) channel else null },
    )

    private fun setText(uid: Int, text: String) {
        interfaces.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_TEXT,
            TspsWriter().i32(uid).string(text).body(),
        ))
    }

    private fun setHidden(uid: Int, hidden: Boolean) {
        interfaces.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_HIDDEN,
            TspsWriter().i32(uid).u8(if (hidden) 1 else 0).body(),
        ))
    }

    @Test
    fun `custom-interface packets before plugin attach are buffered and drained in order`() {
        // Server sends MOTD set_text + set_hidden while the plugin is
        // still mid-attach. Nothing should reach forwarded yet.
        val motdTitle = (30020 shl 16) or 3
        val motdTagline = (30020 shl 16) or 4
        setText(motdTitle, "Welcome to Voidburn")
        setText(motdTagline, "A new way to play")
        setHidden(motdTitle, false)
        assertTrue(forwarded.isEmpty(), "no channel → nothing delivered yet")

        // Plugin attaches. GatewaySession marshals a drain onto the
        // session executor; here we call it directly since the test is
        // single-threaded.
        channelAvailable = true
        interfaces.onPluginChannelAttached()

        assertEquals(3, forwarded.size)
        assertEquals(
            """{"type":"widget_set_text","widgetId":$motdTitle,"text":"Welcome to Voidburn"}""",
            forwarded[0], "first buffered packet drains first")
        assertEquals(
            """{"type":"widget_set_text","widgetId":$motdTagline,"text":"A new way to play"}""",
            forwarded[1])
        assertEquals(
            """{"type":"widget_set_hidden","widgetId":$motdTitle,"hidden":false}""",
            forwarded[2])
    }

    @Test
    fun `packets after attach are sent immediately in order`() {
        channelAvailable = true
        val uid = (30020 shl 16) or 3
        setText(uid, "a")
        setText(uid, "b")
        setText(uid, "c")
        assertEquals(3, forwarded.size, "with channel attached, each call sends immediately")
        assertTrue(forwarded[0].contains("\"text\":\"a\""))
        assertTrue(forwarded[1].contains("\"text\":\"b\""))
        assertTrue(forwarded[2].contains("\"text\":\"c\""))
    }

    @Test
    fun `packets that straddle attach deliver in strict FIFO order`() {
        // Adversarial straddle (v1 review called out: the previous shape of
        // this test drained BEFORE P3 arrived, so it didn't actually exercise
        // the reorder hazard). The real race is:
        //   - P1, P2 buffered
        //   - channel supplier starts returning the live channel
        //     (plugin attached but the drain microtask hasn't run)
        //   - P3 arrives — forwardCustomInterface MUST append P3 and drain
        //     the buffer in order, not send P3 direct and leave P1/P2 for
        //     the later onPluginChannelAttached call
        //   - onPluginChannelAttached runs; it's a no-op because
        //     forwardCustomInterface's own drainReplayBuffer already
        //     flushed everything in order
        // The buffer-then-drain discipline in forwardCustomInterface
        // guarantees this.
        val uid = (30020 shl 16) or 3
        setText(uid, "P1")
        setText(uid, "P2")
        assertTrue(forwarded.isEmpty(), "channel supplier still returns null")

        channelAvailable = true  // channel now live but drain not explicitly run
        setText(uid, "P3")
        assertEquals(3, forwarded.size, "forwardCustomInterface drains before returning")
        assertTrue(forwarded[0].contains("\"text\":\"P1\""), "P1 first")
        assertTrue(forwarded[1].contains("\"text\":\"P2\""), "P2 second")
        assertTrue(forwarded[2].contains("\"text\":\"P3\""), "P3 third")

        interfaces.onPluginChannelAttached()
        assertEquals(3, forwarded.size, "explicit attach drain is a no-op after the FIFO drain")
    }

    @Test
    fun `clearReplayBuffer drops buffered packets without delivering them`() {
        val uid = (30020 shl 16) or 3
        setText(uid, "lost1")
        setText(uid, "lost2")
        assertTrue(forwarded.isEmpty())

        interfaces.clearReplayBuffer()

        // Even if the plugin attaches after clear, the cleared packets
        // never land.
        channelAvailable = true
        interfaces.onPluginChannelAttached()
        assertTrue(forwarded.isEmpty(), "cleared packets stay dropped")
    }

    @Test
    fun `buffer overflow drops newest packets to preserve state-establishing earlier ones`() {
        // Fill the buffer to exactly the cap with large-ish payloads, then
        // try one more big one that doesn't fit. The oldest must survive
        // (they carry widget_open / widget_set_root the plugin needs);
        // the busting newest is the one dropped.
        val bigText = "x".repeat(2048)  // ~2 KiB each; encoded JSON ~2.1 KiB
        val uid = (30020 shl 16) or 3
        var accepted = 0
        // Keep adding until the NEXT bigText would overflow. We don't
        // hardcode 33; the exact fit count depends on the JSON wrapper
        // bytes (per-call), which is why v1's hardcoded 33 wasn't robust.
        while (true) {
            val before = forwarded.size
            val ok = tryAddBigText(uid, bigText, accepted)
            if (!ok) break
            accepted++
            // Buffer-then-drain pattern + no attached channel means ok=true
            // ALWAYS leaves forwarded empty.
            assert(forwarded.size == before)
        }
        // Now try a packet that definitely doesn't fit in the small remaining
        // capacity: use the same bigText which we just failed to add. The
        // reject count should go up but the smaller first-level additions
        // we made must still drain in order.
        channelAvailable = true
        interfaces.onPluginChannelAttached()
        assertEquals(accepted, forwarded.size, "all accepted packets drain exactly once")
        assertTrue(
            forwarded.first().contains("\"text\":\"${bigText}\""),
            "first buffered packet survived")
    }

    @Test
    fun `a smaller packet can still be accepted after a larger one was rejected`() {
        // Drop-newest is per-packet-doesn't-fit, not a sticky "reject
        // everything after any rejection" switch. A short packet that
        // fits in remaining capacity after a big-packet rejection must
        // be accepted — the plugin needs every state update it can get.
        // v1 of the overflow test wrongly expected the opposite.
        val uid = (30020 shl 16) or 3
        val huge = "y".repeat(70_000)  // bigger than the 64 KiB cap on its own
        assertFalse(interfaces_forwardWillAccept(huge),
            "a single packet past the cap rejects outright")
        // The buffer is empty; a short packet fits.
        setText(uid, "short")
        channelAvailable = true
        interfaces.onPluginChannelAttached()
        assertEquals(1, forwarded.size, "short packet accepted and drained")
        assertTrue(forwarded.single().contains("\"text\":\"short\""))
    }

    @Test
    fun `byte budget is counted in UTF-8 bytes not UTF-16 code units`() {
        // v1 review P2a: Kotlin's String.length is UTF-16 code units. The
        // wire is UTF-8. An accented string is 1 code unit per char but
        // 2 bytes per char, so a UTF-16-measured 64 KiB cap could let a
        // UTF-8 payload of ~128 KiB through. v2 counts bytes.
        //
        // Probe: one widget text of 40,000 "é" characters → ~80 KiB UTF-8
        // JSON frame. Must reject.
        val uid = (30020 shl 16) or 3
        val accented = "é".repeat(40_000)
        val encoded = gateway.down.VoidburnPluginJson.encode(
            "widget_set_text", mapOf("widgetId" to uid, "text" to accented),
        )
        // Sanity: this encoded string is bigger in UTF-8 than in code units.
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size > encoded.length,
            "sanity: UTF-8 bytes > UTF-16 code units for non-ASCII")
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size > 64 * 1024,
            "sanity: probe is sized to overflow the 64 KiB byte cap")
        setText(uid, accented)
        assertTrue(interfaces_bufferIsEmpty(),
            "a single >64 KiB UTF-8 payload must reject, not sneak through on code-unit length")
    }

    // --- internal helpers ---

    private fun tryAddBigText(uid: Int, text: String, index: Int): Boolean {
        val sizeBefore = replayBufferSize()
        setText(uid, text)
        return replayBufferSize() > sizeBefore
    }

    private fun interfaces_forwardWillAccept(text: String): Boolean {
        val uid = (30020 shl 16) or 3
        val before = replayBufferSize()
        setText(uid, text)
        return replayBufferSize() > before
    }

    private fun interfaces_bufferIsEmpty(): Boolean = replayBufferSize() == 0

    /** Reflection: peek at the buffer size without widening production API. */
    private fun replayBufferSize(): Int {
        val field = Interfaces::class.java.getDeclaredField("replayBuffer")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(interfaces) as ArrayDeque<*>).size
    }


    @Test
    fun `second attach after a detach drains anything buffered during the gap`() {
        // Plugin attached once, drained empty, then detached (e.g. socket
        // closed). More custom-interface packets arrive while the channel
        // is null again; those re-enter the buffer. A second attach
        // drains them.
        channelAvailable = true
        val uid = (30020 shl 16) or 3
        setText(uid, "pre")
        interfaces.onPluginChannelAttached()
        assertEquals(1, forwarded.size)

        channelAvailable = false
        setText(uid, "during-detach")
        assertEquals(1, forwarded.size, "detached; nothing more delivered yet")

        channelAvailable = true
        interfaces.onPluginChannelAttached()
        assertEquals(2, forwarded.size)
        assertTrue(forwarded[1].contains("\"text\":\"during-detach\""))
    }
}
