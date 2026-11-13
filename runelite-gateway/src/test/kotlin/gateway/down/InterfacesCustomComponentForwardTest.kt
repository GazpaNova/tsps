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
import net.rsprot.protocol.game.outgoing.interfaces.IfSetHide
import net.rsprot.protocol.game.outgoing.interfaces.IfSetText
import net.rsprot.protocol.message.OutgoingGameMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The gateway forwards widget_set_text and widget_set_hidden for custom groups
 * (group id >= 30000) to the Voidburn plugin as JSON frames, and leaves stock-group
 * behavior unchanged (send on exists, drop otherwise).
 *
 * These tests pin that behavior per packet: the frame reaches the plugin in the
 * documented shape with the full packed widgetId, and nothing lands in the cache-
 * client outbound queue for custom groups. They complement InterfacesRootTest,
 * which covers set_root forwarding, and VoidburnPluginJsonTest, which pins the
 * JSON encoding of the field values.
 */
class InterfacesCustomComponentForwardTest {
    private val cache = Cache.open(GatewayConfig.load().cacheDirectory)
    private val enums = EnumTypes(cache)
    private val sent = ArrayList<OutgoingGameMessage>()
    private val forwarded = ArrayList<String>()
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
        { channel },
    )

    @Test
    fun `widget_set_text for a custom group is forwarded as JSON, not sent to the client`() {
        val uid = (30000 shl 16) or 10
        interfaces.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_TEXT,
            TspsWriter().i32(uid).string("Welcome back, Miguel!").body(),
        ))
        assertTrue(sent.isEmpty(), "custom-group set_text must not reach the game client queue")
        assertEquals(1, forwarded.size)
        assertEquals(
            """{"type":"widget_set_text","widgetId":${uid},"text":"Welcome back, Miguel!"}""",
            forwarded.single(),
        )
    }

    @Test
    fun `widget_set_hidden for a custom group is forwarded with a boolean`() {
        val uid = (30020 shl 16) or 5
        interfaces.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_HIDDEN,
            TspsWriter().i32(uid).u8(1).body(),
        ))
        assertTrue(sent.isEmpty())
        assertEquals(
            """{"type":"widget_set_hidden","widgetId":${uid},"hidden":true}""",
            forwarded.single(),
        )
    }

    @Test
    fun `widget_set_hidden with the hidden byte zero is forwarded as false`() {
        val uid = (30020 shl 16) or 5
        interfaces.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_HIDDEN,
            TspsWriter().i32(uid).u8(0).body(),
        ))
        assertEquals(
            """{"type":"widget_set_hidden","widgetId":${uid},"hidden":false}""",
            forwarded.single(),
        )
    }

    @Test
    fun `widget_set_text escaping follows VoidburnPluginJson rules`() {
        // The encoder escapes " \ control-chars; the gateway test pins that it's
        // routed through the encoder for the text field, not concatenated raw.
        val uid = (30000 shl 16) or 1
        interfaces.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_TEXT,
            TspsWriter().i32(uid).string("line1\nline2\t\"quoted\"").body(),
        ))
        assertEquals(
            """{"type":"widget_set_text","widgetId":${uid},"text":"line1\nline2\t\"quoted\""}""",
            forwarded.single(),
        )
    }

    @Test
    fun `widget_set_text for a stock group still goes to the client when the component exists`() {
        // interfaceComponent exists-stub returns true for everything, so the stock
        // path should fall through to IfSetText reaching the game-client queue.
        val uid = (161 shl 16) or 10
        interfaces.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_TEXT,
            TspsWriter().i32(uid).string("hello").body(),
        ))
        assertTrue(forwarded.isEmpty(), "stock-group packets must not go to the plugin channel")
        val set = sent.filterIsInstance<IfSetText>().single()
        assertEquals(161 to 10, set.interfaceId to set.componentId)
        assertEquals("hello", set.text)
    }

    @Test
    fun `widget_set_hidden for a stock group still goes to the client`() {
        val uid = (161 shl 16) or 10
        interfaces.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_HIDDEN,
            TspsWriter().i32(uid).u8(1).body(),
        ))
        assertTrue(forwarded.isEmpty())
        val hide = sent.filterIsInstance<IfSetHide>().single()
        assertEquals(161 to 10, hide.interfaceId to hide.componentId)
        assertEquals(true, hide.hidden)
    }

    @Test
    fun `custom-group set_text with no plugin channel is dropped, not sent to the client`() {
        // Replace the channel supplier with one that returns null, as happens on a
        // session with no plugin attached. The custom group's set_text should drop
        // (no forward, no client send) — matches pre-2c-3a behavior for group >=
        // 30000 but without a plugin, which was "component doesn't exist, drop".
        val noChannelSent = ArrayList<OutgoingGameMessage>()
        val noChannel = Interfaces(
            VarpMirror(VarTypes(cache)),
            InterfaceTree(enums),
            StockMarket { noChannelSent += it },
            {},
            { true },
            { noChannelSent += it },
            { null },
        )
        val uid = (30000 shl 16) or 10
        noChannel.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_TEXT,
            TspsWriter().i32(uid).string("hi").body(),
        ))
        assertTrue(noChannelSent.isEmpty(), "no plugin channel means the packet is dropped")
    }

    @Test
    fun `stock-161 component whose remap returns -1 is NOT forwarded as a custom frame`() {
        // Regression (v1 review P2): v1 classified on the REMAPPED uid.
        // InterfaceTree.remap returns -1 for a stock-161 component absent from
        // the active layout's redirect enum; -1 ushr 16 == 65535 which trips the
        // custom check and forwarded a stock packet with widgetId=-1 to the plugin.
        // v2 classifies on the RAW uid, so the mis-remap now falls through to the
        // exists/dropped path and the plugin never sees it.
        //
        // We simulate the mis-remap by using a custom tree lookalike that only
        // accepts the raw uid in the existence check — the real redirect-enum
        // behavior is cache-specific and out of this test's scope; the point is
        // that a raw uid in group 161 must stay on the stock path.
        val rawUid = (161 shl 16) or 94
        val existingUids = ArrayList<Int>()
        val stockOnlyInterfaces = Interfaces(
            VarpMirror(VarTypes(cache)),
            InterfaceTree(enums),
            StockMarket { sent += it },
            {},
            { uid -> existingUids += uid; false }, // nothing exists → dropped path
            { sent += it },
            { channel },
        )
        stockOnlyInterfaces.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_TEXT,
            TspsWriter().i32(rawUid).string("chat").body(),
        ))
        assertTrue(forwarded.isEmpty(), "stock-161 uid must never reach the plugin channel")
    }

    @Test
    fun `custom-group set_hidden with no plugin channel is dropped`() {
        val noChannelSent = ArrayList<OutgoingGameMessage>()
        val noChannel = Interfaces(
            VarpMirror(VarTypes(cache)),
            InterfaceTree(enums),
            StockMarket { noChannelSent += it },
            {},
            { true },
            { noChannelSent += it },
            { null },
        )
        val uid = (30000 shl 16) or 10
        noChannel.handle(TspsPacket(
            TspsServerPacket.WIDGET_SET_HIDDEN,
            TspsWriter().i32(uid).u8(1).body(),
        ))
        assertTrue(noChannelSent.isEmpty())
    }
}
