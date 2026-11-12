package gateway.plugin

import gateway.session.VoidburnPluginChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Looks up a logged-in player by username so a plugin WebSocket connection can attach
 * itself to the matching GatewaySession.
 *
 * Each GatewaySession registers once at login and unregisters once at disconnect. The
 * registry is the only place where usernames map to the session-side "attach the channel
 * here" callback, so the WebSocket handler can live without a direct reference to any
 * particular session. Lookups are case-sensitive on the OSRS display name the session
 * knows itself by.
 *
 * Concurrency: registration and lookup both happen off the main event thread (login and
 * WebSocket accept happen on their respective pools), so the map is a ConcurrentHashMap.
 * A session that re-registers (reconnecting player) replaces any previous entry.
 *
 * Security note for Phase 2c: this patch does NOT authenticate attach messages — any
 * connection that names a username wins the lookup. Safe for Phase 2b only because the
 * capability bit stays off everywhere and no custom-interface packet is actually sent to
 * any session yet. Phase 2c MUST add a session token (issued at login and verified on
 * attach) before the capability bit can be set on any session.
 */
class VoidburnPluginRegistry {
    private val sessions = ConcurrentHashMap<String, (VoidburnPluginChannel?) -> Unit>()

    /**
     * Register a session under its username. The `attach` callback receives a channel when
     * a plugin connects, and null when the plugin disconnects. The session typically
     * writes the channel into its voidburnPluginChannel field (see GatewaySession).
     */
    fun register(username: String, attach: (VoidburnPluginChannel?) -> Unit) {
        sessions[username] = attach
    }

    /**
     * Remove a session from the registry. Called on disconnect; idempotent.
     */
    fun unregister(username: String) {
        sessions.remove(username)
    }

    /**
     * Attach a channel to the session for `username`. Returns true if a session was found
     * and the attach callback was invoked; false when no session with that name is
     * currently registered (the plugin tried to attach for a player who isn't logged in).
     */
    fun attach(username: String, channel: VoidburnPluginChannel): Boolean {
        val attach = sessions[username] ?: return false
        attach(channel)
        return true
    }

    /**
     * Detach the channel attached for `username`, if any. Called when the plugin's
     * WebSocket closes. No-op when the session is already gone.
     */
    fun detach(username: String) {
        sessions[username]?.invoke(null)
    }

    /** Current number of registered sessions. Tests only. */
    internal val size: Int get() = sessions.size
}
