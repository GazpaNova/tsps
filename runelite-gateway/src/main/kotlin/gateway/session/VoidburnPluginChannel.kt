package gateway.session

/**
 * Side channel from the gateway to a Voidburn RuneLite plugin that renders server-defined
 * custom interfaces (group id >= 30000). Null until a plugin connects for the session.
 *
 * The browser client fetches custom-interface definitions over HTTP from tsps and renders
 * them natively; RuneLite has no equivalent in its wire protocol, so custom-interface
 * packets are dropped in the gateway's Interfaces translator unless a channel like this
 * is attached. When one is attached, those packets are serialized as JSON messages and
 * sent over this channel; the plugin reconstructs the interface on its side.
 *
 * The plumbing lives here (Phase 2a); the actual WebSocket implementation and the plugin
 * that establishes the channel come in Phase 2b. Until the channel is attached — which
 * is to say, on every current session — behavior is byte-identical to before: drops
 * happen exactly as they did, and the plugin branch in Interfaces is dead code.
 *
 * Message schema is intentionally JSON (not a binary rsprot encoding) so the plugin can
 * parse it with any JSON library and so the schema can be extended without changing a
 * protocol module. Shapes are documented in the forward helpers that build them.
 */
interface VoidburnPluginChannel {
    /**
     * Send one serialized message to the plugin. The channel decides how to deliver it
     * (WebSocket in Phase 2b). Called from the gateway's event thread, so
     * implementations must not block.
     *
     * The gateway treats delivery as best-effort: a failed send does not retry and does
     * not throw. The plugin is expected to request a full definition refresh on
     * reconnect if it misses messages.
     */
    fun send(message: String)
}
