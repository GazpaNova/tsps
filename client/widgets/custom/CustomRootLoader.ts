/**
 * Fetches the definition of a server-defined root interface before applying a set_root.
 *
 * The browser's open_sub handler already fetches unknown custom interface definitions
 * via /api/interfaces/<groupId> before mounting them, buffering any text/flag packets
 * that arrive in the meantime. The set_root path originally did not: it called
 * WidgetManager.setRootInterface directly and left the root pointer at an undefined
 * group when the definition wasn't in the cache.
 *
 * This loader restores symmetry between the two paths for roots. For a group id in the
 * custom range that isn't loaded yet it fetches the definition first, then runs the
 * caller-supplied "apply set_root" block with the group available. For cache-backed
 * roots (161/164/548/601/378 and anything else below the custom range) it is a no-op:
 * the caller runs synchronously as before.
 *
 * Cancellation: only one root is in flight at a time, so a single identity token on the
 * loader is enough. If `load` is called again before an earlier fetch resolves, the
 * earlier fetch's `.then` handler sees the token mismatch and returns without mounting.
 * Pending updates for the orphaned group are cancelled so the queue map doesn't leak.
 * The caller is also responsible for calling `cancel()` when the owning client is
 * reset or disposed, so a late-arriving fetch result doesn't fire applySetRoot into a
 * torn-down widget tree.
 *
 * Ordering contract with the server: the server MAY send component updates (set_text,
 * set_flags, set_item, set_hidden etc. — payloads with a widget `uid`) in the same
 * batch as a set_root for a not-yet-loaded custom root; they are buffered via
 * PendingInterfaceUpdates and replayed after mount. The server SHOULD NOT send
 * payloads without a widget `uid` (such as run_script or close_sub targeting the new
 * root) in the same batch as the set_root — those bypass the buffer and will act
 * against the previous root or nothing at all. For a welcome-screen use case this is
 * trivially satisfied: the server sends set_root + a handful of sendString/
 * sendInterfaceFlags calls, all of which carry a uid and queue correctly. More
 * complex custom roots that need scripts at mount time should run them in their own
 * widget's onLoad handlers rather than as side-of-open packets.
 *
 * The loader does not touch widgets, varcs, transfers or the CS2 handler cache — those
 * remain the caller's responsibility, run from inside `applySetRoot`. That keeps the
 * set_root blob in OsrsClient unchanged for stock roots and keeps this module small
 * enough to unit-test with mock dependencies.
 */
import { PendingInterfaceUpdates } from "./PendingInterfaceUpdates";

/** Minimum group id considered a server-defined (custom) interface. */
export const CUSTOM_INTERFACE_START = 30_000;

export interface CustomRootLoaderDeps {
    /** Check whether the widget manager already has a group's definition loaded. */
    isGroupLoaded(groupId: number): boolean;
    /** Fetch the definition for a server-defined group over HTTP. */
    fetchInterfaceDefinition(groupId: number): Promise<any | undefined>;
    /** Install a fetched definition into dynamic widget groups. Returns false on malformed. */
    setCustomInterface(definition: any): boolean;
    /** Replay a buffered payload against the widget tree. */
    handleWidgetPayload(payload: unknown): void;
    /** The buffer used while a definition is in flight; the same instance the caller uses. */
    pendingInterfaceUpdates: PendingInterfaceUpdates;
}

interface PendingRoot {
    groupId: number;
}

export class CustomRootLoader {
    private readonly deps: CustomRootLoaderDeps;
    /** Latest in-flight root fetch; null when no fetch is in flight. */
    private pending: PendingRoot | null = null;

    constructor(deps: CustomRootLoaderDeps) {
        this.deps = deps;
    }

    /**
     * Run `applySetRoot` for a set_root payload. For a stock (cache-backed) root or a
     * custom root whose definition has already been fetched, this calls applySetRoot
     * synchronously and returns. For an unloaded custom root, it starts a fetch,
     * buffers component updates for that group, and calls applySetRoot once the
     * definition is installed.
     *
     * Returns true if applySetRoot ran synchronously, false if it was deferred.
     * A false return does NOT indicate failure — the caller typically ignores it;
     * it's exposed mainly for tests.
     */
    load(groupId: number, applySetRoot: () => void): boolean {
        const id = groupId | 0;
        // Any fresh set_root supersedes a prior in-flight fetch, regardless of whether
        // the new group matches the pending one. For a different group we're switching
        // roots outright. For the same group we're taking the sync path (fast path or
        // definition-now-present), so any queue still open from the earlier attempt
        // belongs to a stale attempt and would otherwise strand any buffered packets
        // for that group indefinitely.
        if (this.pending) {
            this.deps.pendingInterfaceUpdates.cancel(this.pending.groupId);
            this.pending = null;
        }
        if (id < CUSTOM_INTERFACE_START || this.deps.isGroupLoaded(id)) {
            // Cache-backed root, or a custom root already loaded earlier in the session.
            applySetRoot();
            return true;
        }
        const token: PendingRoot = { groupId: id };
        this.pending = token;
        this.deps.pendingInterfaceUpdates.open(id);
        this.deps
            .fetchInterfaceDefinition(id)
            .then((definition) => this.onFetchResolved(token, id, applySetRoot, definition))
            .catch((error) => this.onFetchRejected(token, id, error));
        return false;
    }

    /**
     * Discard any in-flight root fetch. Call this from the owning client's reset and
     * dispose paths before widget/session state is cleared, so a late-arriving fetch
     * result does not install a definition and fire applySetRoot into a torn-down
     * widget tree. Idempotent.
     */
    cancel(): void {
        if (this.pending) {
            this.deps.pendingInterfaceUpdates.cancel(this.pending.groupId);
            this.pending = null;
        }
    }

    private onFetchResolved(
        token: PendingRoot,
        id: number,
        applySetRoot: () => void,
        definition: unknown,
    ): void {
        // Another set_root arrived first, or the owner called cancel() — drop this result.
        if (this.pending !== token) return;
        this.pending = null;
        if (!definition || !this.deps.setCustomInterface(definition)) {
            console.error(
                `[CustomRootLoader] cannot set root ${id}: it is not in the cache and has no server definition`,
            );
            this.deps.pendingInterfaceUpdates.cancel(id);
            return;
        }
        applySetRoot();
        // Flush component updates that arrived while the definition was in flight.
        this.deps.pendingInterfaceUpdates.flush(id, (queued) =>
            this.deps.handleWidgetPayload(queued),
        );
    }

    private onFetchRejected(token: PendingRoot, id: number, error: unknown): void {
        // Another set_root arrived first — a stale fetch's rejection must not touch
        // the newer request's queue or token state. Same guard as the resolved path.
        if (this.pending !== token) return;
        this.pending = null;
        console.error(
            `[CustomRootLoader] fetch for root ${id} failed; keeping previous root`,
            error,
        );
        this.deps.pendingInterfaceUpdates.cancel(id);
    }
}
