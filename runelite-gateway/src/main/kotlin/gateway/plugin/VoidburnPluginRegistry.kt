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
 * Concurrency and ownership: registration and lookup both happen off the main event
 * thread (login and WebSocket accept happen on their respective pools). Every
 * register()/attach() returns an opaque handle (Registration / Attachment). Unregister
 * and detach are owner-scoped: they only mutate state when the current slot is still
 * owned by the handle. A stale cleanup — session A's close() firing after session B
 * replaced its registration, or channel A's close() firing after channel B replaced its
 * attachment — becomes a no-op and leaves the replacement intact.
 *
 * Ordering: the map mutation and the attach-callback publication are serialized under
 * one monitor so that two concurrent attaches (or an attach racing a detach) can never
 * leave the registry pointing at one channel while the session's own field points at
 * another. JVM monitors are reentrant, so re-entry does not automatically deadlock.
 * Callbacks must be simple, non-blocking and non-reentrant by contract. This is what the
 * current production callback does: one field assignment (see GatewaySession's
 * voidburnPluginChannel setter path) and no I/O.
 *
 * Security note for Phase 2c: this patch does NOT authenticate attach messages. Attach
 * only writes into the registry; whether the GatewaySession actually uses the channel is
 * gated separately by GatewaySession.voidburnPluginAuthenticated, which Phase 2b never
 * sets to true. Phase 2c adds a session token (issued at login, verified on attach) and
 * flips the authenticated flag once the token checks out. Until then, forwarding is
 * inert regardless of what the WebSocket side does.
 */
class VoidburnPluginRegistry {
    /**
     * Opaque handle returned by [register]. Pass this same handle to [unregister] so a
     * stale unregister can be detected and ignored when the slot has been replaced.
     */
    class Registration internal constructor(
        internal val username: String,
        internal val attach: (VoidburnPluginChannel?) -> Unit,
    )

    /**
     * Opaque handle returned by [attach]. Pass this same handle to [detach] so a stale
     * detach (channel A closing after channel B took its place) becomes a no-op.
     */
    class Attachment internal constructor(
        internal val registration: Registration,
        internal val channel: VoidburnPluginChannel,
    )

    private val sessions = ConcurrentHashMap<String, Registration>()
    private val attachments = ConcurrentHashMap<String, Attachment>()

    /**
     * Guards the paired `map mutation` + `callback invocation` steps of register,
     * unregister, attach and detach. The maps are still ConcurrentHashMaps so that
     * benign lookups outside these four methods stay lock-free; the lock exists to make
     * the two publish steps (map state and session-side callback) atomic together.
     */
    private val publishLock = Any()

    /**
     * Register a session under its username. The `attach` callback receives a channel
     * when a plugin connects, and null when the plugin disconnects. The session typically
     * writes the channel into its voidburnPluginChannel field (see GatewaySession).
     *
     * A re-register for the same username (reconnecting player) replaces the previous
     * registration. Any attachment held by the previous registration is cleared and that
     * registration's attach callback is invoked with null, so the old session's channel
     * reference drops to null before the new session owns the slot.
     */
    fun register(username: String, attach: (VoidburnPluginChannel?) -> Unit): Registration {
        val newReg = Registration(username, attach)
        synchronized(publishLock) {
            val previousReg = sessions.put(username, newReg)
            if (previousReg != null) {
                // The old session is being replaced. Clear any attachment that was
                // bound to the old registration and notify the old session that its
                // channel is gone. Callback stays inside the lock so no concurrent
                // attach can slip between map mutation and session-field publication.
                val previousAttachment = attachments[username]
                if (previousAttachment != null && previousAttachment.registration === previousReg) {
                    attachments.remove(username, previousAttachment)
                    previousReg.attach(null)
                }
            }
        }
        return newReg
    }

    /**
     * Remove this registration from the registry. Owner-scoped: if the slot now holds a
     * different registration (the session was replaced), this call is a no-op. Idempotent
     * when called twice for the same handle.
     *
     * Also clears the registration's attachment, if the slot still holds one owned by
     * this registration — the replacing session's attachment, if any, is untouched.
     */
    fun unregister(registration: Registration) {
        synchronized(publishLock) {
            val removed = sessions.remove(registration.username, registration)
            if (!removed) return
            val attachment = attachments[registration.username]
            if (attachment != null && attachment.registration === registration) {
                attachments.remove(registration.username, attachment)
            }
        }
    }

    /**
     * Attach a channel to the session for `username`. Returns an Attachment handle when a
     * session was found and the attach callback was invoked; null when no session with
     * that name is currently registered (the plugin tried to attach for a player who
     * isn't logged in).
     *
     * A second attach for the same username (a second plugin instance) replaces the first
     * attachment: the first channel's handle becomes stale, so its later detach() is a
     * no-op. The session's attach callback is invoked with the new channel, overwriting
     * the voidburnPluginChannel field.
     */
    fun attach(username: String, channel: VoidburnPluginChannel): Attachment? {
        synchronized(publishLock) {
            val registration = sessions[username] ?: return null
            val attachment = Attachment(registration, channel)
            // Place the new attachment; we don't inspect the previous one. The previous
            // channel's detach() call will compare handles and find the slot no longer
            // owns it, so it will not clobber this attachment either. Callback stays
            // inside the lock so two concurrent attaches publish in a serialized order:
            // whichever one writes to the map last is also the one whose channel sits
            // in the session's field when the lock is released.
            attachments[username] = attachment
            registration.attach(channel)
            return attachment
        }
    }

    /**
     * Detach the channel this handle represents. Owner-scoped: if the slot holds a
     * different attachment (a replacing channel took over), this call is a no-op. When
     * the detach does take effect, the registration's attach callback is invoked with
     * null to clear the session's voidburnPluginChannel field — but only if the
     * registration itself still owns the slot (a disconnected session left this handle
     * dangling: just drop it without touching the possibly-replacing session's state).
     */
    fun detach(attachment: Attachment) {
        synchronized(publishLock) {
            val removed = attachments.remove(attachment.registration.username, attachment)
            if (!removed) return
            val currentReg = sessions[attachment.registration.username]
            if (currentReg === attachment.registration) {
                // Clearing the session field happens inside the lock so a racing attach
                // can't publish its channel between our map removal and this null
                // notification and have the null clobber the newcomer.
                attachment.registration.attach(null)
            }
        }
    }

    /** Current number of registered sessions. Tests only. */
    internal val registrationCount: Int get() = sessions.size

    /** Current number of live attachments. Tests only. */
    internal val attachmentCount: Int get() = attachments.size
}
