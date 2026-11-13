package com.voidburn.plugin;

import java.util.List;

/**
 * Typed envelope for one frame received from the gateway on the plugin WebSocket.
 *
 * The wire shape is {@code { "type": "<name>", ...fields }} and the gateway's Kotlin
 * side (see runelite-gateway/.../gateway/down/Interfaces.kt and
 * gateway/plugin/VoidburnPluginJson.kt) is the single source of truth for which frame
 * types can arrive. This sealed hierarchy mirrors what Phase 2a plus Phase 2c-1 forwards
 * today — three widget-level events for custom groups (>= 30000). Any other frame type
 * decodes to {@link Unknown} and is logged by the caller, so the plugin stays forward-
 * compatible with a newer gateway without a code change.
 *
 * When the gateway grows a new frame type (e.g. {@code widget_set_text} for component
 * content in Phase 2c-3), add a matching record here and a case in {@link FrameDecoder}.
 *
 * Records are immutable value types; callers freely share {@link InterfaceState}
 * snapshots across threads without additional locking.
 */
public sealed interface Frame permits
    Frame.WidgetOpen,
    Frame.WidgetSetRoot,
    Frame.WidgetOpenSub,
    Frame.Unknown {

    /**
     * Gateway sends this when the server opens a custom interface
     * ({@code WIDGET_OPEN} with group &gt;= 30000). {@code modal} echoes the server's
     * modal flag — advisory on the plugin side.
     */
    record WidgetOpen(int group, boolean modal) implements Frame {}

    /**
     * Gateway sends this when the server makes a custom interface the root
     * ({@code WIDGET_SET_ROOT} with group &gt;= 30000). The plugin should treat this
     * as a reset of the current custom-root view.
     */
    record WidgetSetRoot(int group) implements Frame {}

    /**
     * Gateway sends this when the server mounts a custom sub-interface
     * ({@code WIDGET_OPEN_SUB} with group &gt;= 30000). Carries the raw target uid
     * as seen on the tsps wire, its gateway-mapped form, the sub-interface's group,
     * its interface type (numeric; {@code type} is already taken by the frame
     * discriminator), and the list of component UIDs that should start hidden.
     */
    record WidgetOpenSub(
        int rawTarget,
        int target,
        int group,
        int interfaceType,
        List<Integer> hiddenUids
    ) implements Frame {}

    /**
     * Any frame whose {@code type} discriminator isn't one of the known values above,
     * or whose fields don't decode cleanly. Carries the raw JSON for logging and for
     * forward-compat tests; the plugin ignores Unknown frames at runtime.
     */
    record Unknown(String type, String rawJson) implements Frame {}
}
