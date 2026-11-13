package gateway.down

import gateway.coverage.DropCounter
import gateway.session.InterfaceTree
import gateway.session.VarpMirror
import gateway.session.VoidburnPluginChannel
import gateway.tsps.TspsPacket
import gateway.tsps.TspsReader
import gateway.tsps.TspsServerPacket
import net.rsprot.protocol.game.outgoing.interfaces.IfCloseSub
import net.rsprot.protocol.game.outgoing.interfaces.IfMoveSub
import net.rsprot.protocol.game.outgoing.interfaces.IfOpenSub
import net.rsprot.protocol.game.outgoing.interfaces.IfOpenTop
import net.rsprot.protocol.game.outgoing.interfaces.IfSetAngle
import net.rsprot.protocol.game.outgoing.interfaces.IfSetAnim
import net.rsprot.protocol.game.outgoing.interfaces.IfSetColour
import net.rsprot.protocol.game.outgoing.interfaces.IfSetEventsV2
import net.rsprot.protocol.game.outgoing.interfaces.IfSetHide
import net.rsprot.protocol.game.outgoing.interfaces.IfSetModelV2
import net.rsprot.protocol.game.outgoing.interfaces.IfSetNpcHead
import net.rsprot.protocol.game.outgoing.interfaces.IfSetObject
import net.rsprot.protocol.game.outgoing.interfaces.IfSetPlayerHead
import net.rsprot.protocol.game.outgoing.interfaces.IfSetPosition
import net.rsprot.protocol.game.outgoing.interfaces.IfSetRotateSpeed
import net.rsprot.protocol.game.outgoing.interfaces.IfSetText
import net.rsprot.protocol.game.outgoing.misc.player.RunClientScript
import net.rsprot.protocol.game.outgoing.misc.player.TriggerOnDialogAbort
import net.rsprot.protocol.game.outgoing.misc.player.UpdateStatV2
import net.rsprot.protocol.game.outgoing.varp.VarpLong
import net.rsprot.protocol.message.OutgoingGameMessage

/**
 * tsps's interface, var, script and stat messages as their OSRS packets.
 *
 * tsps bundles some of these (WIDGET_OPEN_SUB carries varps, varbits, hidden components and
 * scripts; WIDGET_RUN_SCRIPT carries vars and inventories). They are split here in the order
 * the browser client applies them.
 */
class Interfaces(
    private val varps: VarpMirror,
    private val tree: InterfaceTree,
    private val stockMarket: StockMarket,
    /** Inventories riding on WIDGET_RUN_SCRIPT, by OSRS inv id. */
    private val inventories: (TspsReader) -> Unit,
    /** Whether the client has this component; anything aimed at one it hasn't is dropped. */
    private val exists: (Int) -> Boolean,
    private val send: (OutgoingGameMessage) -> Unit,
    /**
     * Supplier of the Voidburn RuneLite plugin channel for this session, if a plugin is
     * connected. When it returns non-null, custom-interface packets (group id >= 30000)
     * are serialized as JSON and sent to the plugin instead of being dropped. The
     * default supplier returns null, so behavior is unchanged for stock clients.
     */
    private val voidburnChannel: () -> VoidburnPluginChannel? = { null },
) {
    /** Returns false for a message this translator doesn't handle. */
    fun handle(packet: TspsPacket): Boolean {
        val reader = packet.reader()
        when (packet.opcode) {
            TspsServerPacket.WIDGET_SET_ROOT -> setRoot(reader.u16())
            TspsServerPacket.WIDGET_OPEN -> {
                val group = reader.u16()
                val modal = reader.u8() != 0
                if (group >= CUSTOM_INTERFACE_START) {
                    // Custom interface: forward to plugin if connected, otherwise drop
                    // as before. A non-modal custom-group open is unusual but passed
                    // through as a plain open; the plugin treats "modal" as advisory.
                    if (!forwardCustomInterface("widget_open", mapOf("group" to group, "modal" to modal))) {
                        DropCounter.down(packet.opcode)
                    }
                } else if (!modal) {
                    DropCounter.down(packet.opcode)
                } else {
                    val target = tree.modalOpened(group)
                    send(IfOpenSub(target ushr 16, target and 0xFFFF, group, MODAL))
                }
            }
            TspsServerPacket.WIDGET_CLOSE -> tree.targetOf(reader.u16())?.let { send(IfCloseSub(it ushr 16, it and 0xFFFF)) }
            TspsServerPacket.WIDGET_OPEN_SUB -> openSubBundle(reader)
            TspsServerPacket.WIDGET_CLOSE_SUB -> {
                val raw = reader.i32()
                tree.gameframe(raw, mounted = false)
                val target = tree.remap(raw)
                tree.unmounted(target)
                send(IfCloseSub(target ushr 16, target and 0xFFFF))
            }
            TspsServerPacket.WIDGET_SET_TEXT -> {
                // Classify from the RAW uid before remap. tree.remap can return -1
                // for a stock-161 component absent from the active layout's
                // redirect enum; (-1 ushr 16) == 0xFFFF would wrongly trip the
                // custom-group check and forward a stock packet as if it were a
                // plugin frame with widgetId=-1. tree.remap is a no-op for group
                // != 161, so custom-group uids are unaffected by classifying first.
                val rawUid = reader.i32()
                val text = reader.string()
                if ((rawUid ushr 16) >= CUSTOM_INTERFACE_START) {
                    if (!forwardCustomInterface("widget_set_text", mapOf("widgetId" to rawUid, "text" to text))) {
                        DropCounter.down(packet.opcode)
                    }
                } else {
                    val uid = tree.remap(rawUid)
                    if (exists(uid)) {
                        send(IfSetText(uid ushr 16, uid and 0xFFFF, text))
                    } else {
                        dropped(uid)
                    }
                }
            }
            TspsServerPacket.WIDGET_SET_HIDDEN -> {
                val rawUid = reader.i32()
                val hidden = reader.u8() != 0
                if ((rawUid ushr 16) >= CUSTOM_INTERFACE_START) {
                    if (!forwardCustomInterface("widget_set_hidden", mapOf("widgetId" to rawUid, "hidden" to hidden))) {
                        DropCounter.down(packet.opcode)
                    }
                } else {
                    val uid = tree.remap(rawUid)
                    if (exists(uid)) {
                        send(IfSetHide(uid ushr 16, uid and 0xFFFF, hidden))
                    } else {
                        dropped(uid)
                    }
                }
            }
            TspsServerPacket.WIDGET_SET_ITEM ->
                component(reader) { i, c ->
                    val obj = reader.u16().let { if (it == 0xFFFF) -1 else it }
                    IfSetObject(i, c, obj, reader.i32())
                }
            TspsServerPacket.WIDGET_SET_NPC_HEAD -> component(reader) { i, c -> IfSetNpcHead(i, c, reader.u16()) }
            TspsServerPacket.WIDGET_SET_PLAYER_HEAD -> component(reader) { i, c -> IfSetPlayerHead(i, c) }
            TspsServerPacket.WIDGET_SET_ANIMATION -> component(reader) { i, c -> IfSetAnim(i, c, reader.s16()) }
            TspsServerPacket.WIDGET_SET_ANGLE -> component(reader) { i, c -> IfSetAngle(i, c, reader.u16(), reader.u16(), reader.u16()) }
            TspsServerPacket.WIDGET_SET_ROTATE_SPEED ->
                component(reader) { i, c ->
                    val packed = reader.i32()
                    IfSetRotateSpeed(i, c, packed.toShort().toInt(), (packed ushr 16).toShort().toInt())
                }
            TspsServerPacket.WIDGET_SET_MODEL -> component(reader) { i, c -> IfSetModelV2(i, c, reader.i32()) }
            TspsServerPacket.WIDGET_SET_POSITION -> component(reader) { i, c -> IfSetPosition(i, c, reader.s16(), reader.s16()) }
            TspsServerPacket.WIDGET_SET_COLOUR ->
                component(reader) { i, c ->
                    val rgb = reader.u16()
                    IfSetColour(i, c, (rgb ushr 10) and 0x1F, (rgb ushr 5) and 0x1F, rgb and 0x1F)
                }
            TspsServerPacket.WIDGET_SET_FLAGS -> {
                val uid = tree.remap(reader.i32())
                events(uid, -1, -1, reader.i32())
            }
            TspsServerPacket.WIDGET_SET_FLAGS_RANGE -> {
                val uid = tree.remap(reader.i32())
                events(uid, reader.u16(), reader.u16(), reader.i32())
            }
            TspsServerPacket.WIDGET_SET_FLAGS_RANGE_V2 -> {
                val uid = tree.remap(reader.i32())
                val start = reader.u16().signed16()
                val end = reader.u16().signed16()
                val flags = reader.i32()
                val ops = reader.i32()
                if (exists(uid)) send(IfSetEventsV2(uid ushr 16, uid and 0xFFFF, start, end, withTargetEvents(uid, flags), ops)) else dropped(uid)
            }
            TspsServerPacket.WIDGET_RUN_SCRIPT -> runScriptBundle(reader)
            TspsServerPacket.RUN_CLIENT_SCRIPT -> script(reader.u16(), reader)
            TspsServerPacket.TRIGGER_ONDIALOGABORT -> send(TriggerOnDialogAbort)
            TspsServerPacket.VARP_SMALL -> varp(reader.u16(), reader.u8())
            TspsServerPacket.VARP_LARGE -> varp(reader.u16(), reader.i32())
            TspsServerPacket.VARP_LONG -> send(VarpLong(reader.u16(), reader.i64()))
            TspsServerPacket.VARBIT -> varps.setVarbit(reader.u16(), reader.i32())?.let(send) ?: DropCounter.down(packet.opcode)
            TspsServerPacket.SKILLS_SNAPSHOT, TspsServerPacket.SKILLS_DELTA -> stats(reader)
            else -> return false
        }
        return true
    }

    private fun varp(id: Int, value: Int) {
        when {
            stockMarket.isOfferVarp(id) -> stockMarket.setVarp(id, value)
            else -> varps.setVarp(id, value)?.let(send) ?: DropCounter.down(TspsServerPacket.VARP_LARGE)
        }
    }

    /**
     * tsps shows the welcome screen as the browser's root. Live keeps the game behind it: it logs
     * in on root 165 with the gameframe moved there and the welcome screen as a modal in 165:40,
     * and on Play closes it and moves the gameframe to the player's root. As the root alone, the
     * client had no game view: the login screen stayed behind it, with "Loading - please wait".
     * Any other root change (a game client layout chosen in the settings) moves the mounts the
     * same way, after setting the client's window mode to match.
     */
    private fun setRoot(group: Int) {
        // Custom-interface root (group >= 30000) is not in the cache. The client's IfOpenTop
        // would set the root pointer to an unknown group and leave the player on a blank
        // screen (this is the P1-c blocker the browser custom-root-fetch patch addresses for
        // the browser). On RuneLite, if a Voidburn plugin is connected we forward the
        // root change to it; otherwise we drop and the tsps-side eligibility check keeps
        // RuneLite-no-plugin sessions on the stock welcome flow so this branch never fires.
        if (group >= CUSTOM_INTERFACE_START) {
            if (!forwardCustomInterface("widget_set_root", mapOf("group" to group))) {
                DropCounter.down(TspsServerPacket.WIDGET_SET_ROOT)
            }
            return
        }
        val from = tree.root
        val welcome = welcomeShown
        welcomeShown = group == WELCOME_SCREEN
        if (welcome) send(IfCloseSub(InterfaceTree.LOGIN_ROOT, InterfaceTree.WELCOME_SLOT))
        tree.setRoot(if (welcomeShown) InterfaceTree.LOGIN_ROOT else group)
        // The client's window mode has to match the layout: fixed in a 765x503 canvas, resizable
        // filling the window. Its script 3998 (run by the server, given 0 fixed, 1 resizable
        // classic, 2 resizable modern) sets it, and does nothing when it already matches. The
        // browser sizes itself, so tsps doesn't send it.
        WINDOW_MODE_LAYOUT[tree.root]?.let { send(RunClientScript(SET_LAYOUT_SCRIPT, listOf(it))) }
        send(IfOpenTop(tree.root))
        // The browser moves tsps's mounts to a new root itself; the official client needs each one
        // moved, as live does on Play and on a game client layout change.
        for ((source, target) in tree.moves(from)) send(IfMoveSub(source ushr 16, source and 0xFFFF, target ushr 16, target and 0xFFFF))
        if (welcomeShown) openSub((InterfaceTree.LOGIN_ROOT shl 16) or InterfaceTree.WELCOME_SLOT, WELCOME_SCREEN, MODAL)
    }

    /** Whether the welcome screen is up; Upstream turns closing it into Play. */
    var welcomeShown = false
        private set

    private fun openSub(target: Int, group: Int, type: Int) {
        tree.mounted(target, group)
        send(IfOpenSub(target ushr 16, target and 0xFFFF, group, type))
    }

    /** `WIDGET_OPEN_SUB`: target, group, type, then varps, varbits, hidden uids, pre and post scripts. */
    private fun openSubBundle(reader: TspsReader) {
        val raw = reader.i32()
        val target = tree.remap(raw)
        val group = reader.u16()
        val type = reader.u8()
        repeat(reader.optionalU8()) { varp(reader.u16(), reader.i32()) }
        repeat(reader.optionalU8()) { varps.setVarbit(reader.u16(), reader.i32())?.let(send) }
        val hidden = List(reader.optionalU8()) { reader.i32() }
        val preScripts = scripts(reader)
        val postScripts = scripts(reader)
        preScripts.forEach(send)
        if (group >= CUSTOM_INTERFACE_START) {
            // Custom sub-interface: forward to plugin with the full bundle, else drop.
            // Pre-scripts already ran via the regular path above; the plugin only needs
            // the group/target/type and the component hidden-state list.
            //
            // "type" is reserved as the top-level event discriminator by VoidburnPluginJson,
            // so the sub-interface's numeric interface type goes out as "interfaceType"
            // to avoid the key collision v1 shipped (Codex Phase 2a review).
            val forwarded = forwardCustomInterface(
                "widget_open_sub",
                mapOf("rawTarget" to raw, "target" to target, "group" to group, "interfaceType" to type, "hiddenUids" to hidden),
            )
            if (!forwarded) DropCounter.down(TspsServerPacket.WIDGET_OPEN_SUB)
            return
        }
        tree.gameframe(raw, mounted = true)
        openSub(target, group, type)
        for (uid in hidden) if (exists(uid)) send(IfSetHide(uid ushr 16, uid and 0xFFFF, true)) else dropped(uid)
        postScripts.forEach(send)
    }

    /** `WIDGET_RUN_SCRIPT`: i32 script (-1: vars only), args, u16-counted varps and varbits, inventories. */
    private fun runScriptBundle(reader: TspsReader) {
        val id = reader.i32()
        val args = args(reader)
        repeat(reader.u16()) { varp(reader.i32(), reader.i32()) }
        repeat(reader.u16()) { varps.setVarbit(reader.i32(), reader.i32())?.let(send) }
        if (reader.remaining > 0) inventories(reader)
        if (id >= 0) send(RunClientScript(id, args))
    }

    private fun script(id: Int, reader: TspsReader) = send(RunClientScript(id, args(reader)))

    private fun scripts(reader: TspsReader): List<OutgoingGameMessage> =
        List(reader.optionalU8()) {
            val id = reader.i32()
            RunClientScript(id, if (reader.remaining > 0) args(reader) else emptyList())
        }

    private fun args(reader: TspsReader): List<Any> =
        List(reader.u8()) {
            when (reader.u8()) {
                ARG_STRING -> reader.string()
                ARG_LONG -> reader.i64()
                else -> reader.i32()
            }
        }

    /** Old-format flags carry ops 1-10 in bits 1-10; IF_SETEVENTS_V2 wants them in events2. */
    private fun events(uid: Int, start: Int, end: Int, flags: Int) {
        val ops = (flags ushr 1) and OLD_OP_BITS
        if (!exists(uid)) return dropped(uid)
        send(IfSetEventsV2(uid ushr 16, uid and 0xFFFF, start.signed16(), end.signed16(), withTargetEvents(uid, flags) and (OLD_OP_BITS shl 1).inv(), ops))
    }

    /**
     * The official client only lets a selected spell or Use land on a slot whose events say it's a
     * target (bit 21), and an item's own Use reaches what bits 11-16 allow. tsps leaves them out, as
     * the browser makes its inventory menus another way; live sets them.
     */
    private fun withTargetEvents(uid: Int, flags: Int) = if (uid == INVENTORY) flags or INVENTORY_TARGET_EVENTS else flags

    private var summaryCombat = -1

    private fun stats(reader: TspsReader) {
        repeat(reader.u8()) {
            val stat = reader.u8()
            val experience = reader.i32()
            reader.u8() // base level
            reader.u8() // virtual level
            reader.u8() // boost + 128
            val current = reader.u8()
            send(UpdateStatV2(stat, current, current, experience))
        }
        reader.u16() // total level
        // The browser works the combat level out itself and shows it from this varbit.
        val combat = reader.u8()
        varps.setVarbit(COMBAT_LEVEL_VARBIT, combat)?.let(send)
        summaryCombatLevel(combat)
    }

    /**
     * The character summary (712) shows varc 52, which the official client only sets by running
     * script 3954 (the browser sets the varc itself). Unset it reads -1. The script lays out the
     * summary rows, so it waits until 712 is mounted and runs again only when the level changes.
     */
    private fun summaryCombatLevel(combat: Int) {
        if (combat == summaryCombat || !exists(SUMMARY_CONTENTS)) return
        summaryCombat = combat
        send(RunClientScript(SUMMARY_COMBAT_SCRIPT, listOf(SUMMARY_CONTENTS, SUMMARY_CLICK_LAYER, combat)))
    }

    private inline fun component(reader: TspsReader, message: (Int, Int) -> OutgoingGameMessage) {
        val uid = tree.remap(reader.i32())
        val built = message(uid ushr 16, uid and 0xFFFF)
        if (exists(uid)) send(built) else dropped(uid)
    }

    private fun dropped(uid: Int) = DropCounter.missingComponent(uid)

    private fun TspsReader.optionalU8() = if (remaining > 0) u8() else 0

    /**
     * Serializes a custom-interface event as JSON and sends it to the plugin, returning
     * true when a plugin is connected and the message was handed off (delivery is
     * best-effort; the channel decides what "delivered" means). Returns false when no
     * plugin channel is attached, so the caller can drop as before.
     *
     * Message shape: `{"type":<type>, ...fields}`. Keys are stable; new event types can
     * be added without breaking existing ones because the plugin ignores unknowns.
     * Everything is primitives + lists of primitives, so the encoder stays trivial and
     * the plugin can use any JSON library.
     */
    private fun forwardCustomInterface(type: String, fields: Map<String, Any?>): Boolean {
        val channel = voidburnChannel() ?: return false
        channel.send(VoidburnPluginJson.encode(type, fields))
        return true
    }

    private fun Int.signed16() = if (this == 0xFFFF) -1 else this

    private companion object {
        const val MODAL = 0
        const val INVENTORY = 149 shl 16

        /** Live's inventory events beyond tsps's: Use on ground items, NPCs, locs, players, items and components (11-16), and being a target (21). */
        const val INVENTORY_TARGET_EVENTS = (0x3F shl 11) or (1 shl 21)
        const val WELCOME_SCREEN = 378
        const val SET_LAYOUT_SCRIPT = 3998

        /** Each gameframe root's layout, as script 3998 takes it. */
        val WINDOW_MODE_LAYOUT = mapOf(548 to 0, 161 to 1, 164 to 2)
        const val CUSTOM_INTERFACE_START = 30_000
        const val ARG_STRING = 0
        const val ARG_LONG = 2
        const val OLD_OP_BITS = 0x3FF
        const val COMBAT_LEVEL_VARBIT = 13027
        const val SUMMARY_COMBAT_SCRIPT = 3954
        const val SUMMARY_CONTENTS = (712 shl 16) or 2
        const val SUMMARY_CLICK_LAYER = (712 shl 16) or 3
    }
}
