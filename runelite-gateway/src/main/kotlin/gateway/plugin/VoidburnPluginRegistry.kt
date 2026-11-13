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
 * Authentication: attach() now carries the token submitted by the plugin and the
 * registry hands it to the session's authorize callback. Only a matching token lets
 * the attach succeed — no match, attach returns null and the slot is untouched. The
 * bind itself IS the authorization: a non-null channel reaches the session's field
 * only through this gate, so Interfaces consults `voidburnPluginChannel` directly
 * without a separate authenticated flag. Replacement follows the same rule: attach B
 * with a bad token leaves A attached; attach B with a valid token (fresh plugin
 * instance for the same session) replaces A in the normal way.
 */
class VoidburnPluginRegistry {
    /**
     * Opaque handle returned by [register]. Pass this same handle to [unregister] so a
     * stale unregister can be detected and ignored when the slot has been replaced.
     */
    class Registration internal constructor(
        internal val username: String,
        internal val attach: (VoidburnPluginChannel?) -> Unit,
        internal val authorize: (token: String) -> Boolean,
        internal val onClick: (Attachment, VoidburnPluginMessages.Click) -> Unit,
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
     * Register a session under its username.
     *
     * The `attach` callback receives a channel when a plugin connects (and has been
     * authorized), and null when the plugin disconnects. The session typically writes
     * the channel into its voidburnPluginChannel field (see GatewaySession).
     *
     * The `authorize` callback takes the token the attaching plugin submitted and
     * returns true iff it is valid for this session. It is called inside the registry's
     * publish lock, so it must stay simple (constant-time equality check against a
     * token the session minted at login) and must not re-enter the registry.
     *
     * A re-register for the same username (reconnecting player) replaces the previous
     * registration. Any attachment held by the previous registration is cleared and
     * that registration's attach callback is invoked with null, so the old session's
     * channel reference drops to null before the new session owns the slot.
     */
    internal fun register(
        username: String,
        attach: (VoidburnPluginChannel?) -> Unit,
        authorize: (token: String) -> Boolean,
        onClick: (Attachment, VoidburnPluginMessages.Click) -> Unit = { _, _ -> },
    ): Registration {
        val newReg = Registration(username, attach, authorize, onClick)
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
                // Symmetric with register-replacement: notify the session that its
                // channel is gone, so voidburnPluginChannel drops to null as part of
                // the same lifecycle step that unregistered. Owner-scoped: the
                // callback is captured in THIS registration, which belongs to the
                // closing session — a replacement session would have a different
                // registration whose callback writes into a different session's
                // field, so this invocation cannot reach into a replacement.
                registration.attach(null)
            }
        }
    }

    /**
     * Attach a channel to the session for `username`, authorizing with `token`. Returns
     * an Attachment handle when a session was found AND the token authorized; null when
     * no session with that name is currently registered (the plugin tried to attach for
     * a player who isn't logged in), or when the token did not authorize against the
     * session's current authorize callback.
     *
     * A failed authorization leaves the registry untouched: no attachment is created,
     * the attach callback is not invoked, and any existing attachment for this
     * username (an earlier plugin instance that successfully attached) stays put.
     *
     * A second successful attach for the same username (a second plugin instance with
     * a valid token — same session, same mint) replaces the first attachment: the
     * first channel's handle becomes stale, so its later detach() is a no-op. The
     * session's attach callback is invoked with the new channel, overwriting the
     * voidburnPluginChannel field.
     */
    fun attach(username: String, token: String, channel: VoidburnPluginChannel): Attachment? {
        synchronized(publishLock) {
            val registration = sessions[username] ?: return null
            if (!registration.authorize(token)) return null
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

    /**
     * Dispatch an inbound click frame for an attached plugin. Owner-scoped
     * against the current attachment slot: if the registration has been
     * unregistered or the attachment replaced since this handler captured its
     * handle, the dispatch is a no-op and the stale click goes nowhere.
     * Returns true when the registration's onClick ran; false when the
     * attachment was retired.
     *
     * Runs under the publish lock so the slot cannot flip between the owner
     * check and the callback invocation (same discipline as attach/detach).
     */
    internal fun dispatchClick(attachment: Attachment, click: VoidburnPluginMessages.Click): Boolean {
        synchronized(publishLock) {
            if (!isCurrentLocked(attachment)) return false
            attachment.registration.onClick(attachment, click)
            return true
        }
    }

    /**
     * True iff this attachment is still the live slot AND its registration is
     * still the live session for that username. No callbacks are invoked
     * here.
     *
     * Semantics and the dispatch-time race window: the registry lock is
     * released before this returns. A caller that acts on a true result
     * (typically on the session executor, preparing to issue one upstream
     * packet) accepts a micro-window in which a concurrent retirement
     * could land between the return and the caller's own next step. This
     * window is deliberate for Phase 2d-1's upstream I/O path: the
     * publish lock is never held across a blocking upstream send
     * (`TspsSocket.send` calls `WebSocket.sendBinary(...).join()`, which
     * waits for the socket), because doing so would let one slow tsps
     * upstream freeze every other player's register, attach, detach,
     * and dispatch at this gateway.
     *
     * Contract: a click approved at the final execution-time ownership
     * check may complete after attachment retirement, including
     * performing its still-valid action upstream. No upper latency
     * bound is guaranteed — thread scheduling and socket contention can
     * delay the send. Retirement prevents subsequent ingress
     * (`dispatchClick` refuses, `isCurrent` returns false) and queued
     * tasks that have not yet passed this check. The packet is still
     * bound for the same username's tsps session (registration is
     * per-username; retirement does not switch the upstream socket),
     * and tsps itself validates button clicks against the current
     * interface state — this prevents invalid actions but does not
     * prove harmlessness, nor does it undo an already-approved action.
     */
    internal fun isCurrent(attachment: Attachment): Boolean {
        synchronized(publishLock) { return isCurrentLocked(attachment) }
    }

    /** Must be called while holding [publishLock]. */
    private fun isCurrentLocked(attachment: Attachment): Boolean {
        val current = attachments[attachment.registration.username]
        if (current !== attachment) return false
        val currentReg = sessions[attachment.registration.username]
        return currentReg === attachment.registration
    }

    /** Current number of registered sessions. Tests only. */
    internal val registrationCount: Int get() = sessions.size

    /** Current number of live attachments. Tests only. */
    internal val attachmentCount: Int get() = attachments.size
}
