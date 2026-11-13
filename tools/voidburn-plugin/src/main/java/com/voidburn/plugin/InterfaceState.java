package com.voidburn.plugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks what custom interfaces (group id &gt;= 30000) the gateway has told us are
 * open on the current session, plus the current custom root and any sub-interface
 * mounts beneath it.
 *
 * Scope for Phase 2c-2b: structural state only. Per-component content (widget_set_text,
 * widget_set_hidden) is not forwarded by the gateway yet; when Phase 2c-3 extends
 * forwarding and a component-level state table is added, it will live alongside the
 * {@link Interface} records here.
 *
 * Threading: OkHttp's dispatcher calls {@link #apply(Frame)} from its worker thread,
 * and future RuneLite overlay code will read the state from the client thread. Backing
 * storage is a ConcurrentHashMap, {@code rootGroup} is volatile, and each
 * {@link Interface} record is immutable, so a reader snapshot reflects a consistent
 * per-group state at the moment of read.
 *
 * Cross-field atomicity is NOT guaranteed. Reading {@link #rootGroup()} and then
 * {@link #get(int)} is two independent operations: a reader can observe the new root
 * from one apply() and the still-unmounted interface-entry map from a concurrent
 * write, because volatile + ConcurrentHashMap each only guarantee their own single
 * field. Writes on this class are ordered so the interface entry is populated BEFORE
 * {@link #rootGroup} is published — so a reader that sees the new root is guaranteed
 * to find its entry on a subsequent {@link #get} call. The reverse order (reading
 * entries then the root) can still see stale root values; callers that need both in
 * one snapshot should take a lock or use the future snapshot API (Phase 2c-3).
 */
public final class InterfaceState {
    /**
     * One open custom interface. Carries its group id, the server's modal flag (as
     * delivered in widget_open; advisory on the plugin side) and the list of sub-
     * interfaces mounted beneath it, each with its own state. The record is immutable;
     * state transitions create a new Interface via {@link #withSub} / {@link #withModal}.
     */
    public record Interface(int group, boolean modal, Map<Integer, SubMount> subMounts) {
        public Interface {
            subMounts = Map.copyOf(subMounts);
        }

        Interface withModal(boolean newModal) {
            return new Interface(group, newModal, subMounts);
        }

        Interface withSub(SubMount mount) {
            Map<Integer, SubMount> next = new java.util.HashMap<>(subMounts);
            next.put(mount.target(), mount);
            return new Interface(group, modal, next);
        }
    }

    /**
     * One sub-interface mounted into a parent via widget_open_sub. Carries the raw
     * and mapped target UIDs, the sub's group and type, and the set of component
     * UIDs that were told to start hidden.
     */
    public record SubMount(
        int rawTarget,
        int target,
        int group,
        int interfaceType,
        Set<Integer> hiddenUids
    ) {
        public SubMount {
            hiddenUids = Set.copyOf(hiddenUids);
        }
    }

    /** Current custom root, if the server set one; null when no custom root is active. */
    private volatile Integer rootGroup = null;

    /** Open custom interfaces keyed by their group id. */
    private final Map<Integer, Interface> open = new ConcurrentHashMap<>();

    /**
     * Apply one frame to the state. Called from OkHttp's dispatcher thread. Unknown
     * frames and frames the state doesn't model (anything beyond the three structural
     * events) are no-ops so new gateway frames don't crash the plugin before a
     * matching apply() case lands.
     */
    public void apply(Frame frame) {
        if (frame instanceof Frame.WidgetOpen o) {
            open.compute(o.group(), (g, prev) -> prev == null
                ? new Interface(g, o.modal(), Collections.emptyMap())
                : prev.withModal(o.modal()));
        } else if (frame instanceof Frame.WidgetSetRoot r) {
            // Populate the Interface entry BEFORE publishing rootGroup. A client
            // thread reader that observes the new rootGroup will then always find a
            // matching entry on its next get() call. Previously these ran in the
            // opposite order and a reader could race in between.
            open.computeIfAbsent(r.group(), g -> new Interface(g, true, Collections.emptyMap()));
            rootGroup = r.group();
        } else if (frame instanceof Frame.WidgetOpenSub s) {
            SubMount mount = new SubMount(
                s.rawTarget(), s.target(), s.group(), s.interfaceType(),
                new HashSet<>(s.hiddenUids())
            );
            // The parent group lives in the HIGH 16 bits of target; the gateway has
            // already split that into "target" (full uid), but the parent group is
            // what maps a sub-mount to its parent interface in our state.
            int parentGroup = s.target() >>> 16;
            open.compute(parentGroup, (g, prev) -> prev == null
                // We might see widget_open_sub before widget_open if the server batches
                // them unusually; fabricate a modal=true parent so the sub has a home.
                // (widget_open is typically first in the real sequence.)
                ? new Interface(g, true, Map.of(mount.target(), mount))
                : prev.withSub(mount));
        }
        // Frame.Unknown and any future unmatched type: no state change.
    }

    /** The current custom root group, or empty when no custom root is active. */
    public Optional<Integer> rootGroup() {
        Integer r = rootGroup;
        return r == null ? Optional.empty() : Optional.of(r);
    }

    /** Snapshot of the given group's open-state, or empty when it isn't currently open. */
    public Optional<Interface> get(int group) {
        return Optional.ofNullable(open.get(group));
    }

    /** True iff a custom interface with this group id is currently open. */
    public boolean isOpen(int group) {
        return open.containsKey(group);
    }

    /**
     * Clear the entire state. Called by the plugin when a connection is being
     * retired (plugin shutdown, config change forcing reconnect) so a new session
     * cannot inherit the previous session's root and mounted interfaces.
     *
     * Ordering: to avoid a reader observing a now-cleared interface entry while
     * rootGroup still points at it, drop rootGroup first (so no new reader will
     * follow the pointer) and then clear the entry map.
     *
     * Caller contract: this must run AFTER the owning {@link PluginConnection} has
     * fully stopped so no in-flight {@link #apply(Frame)} call races the clear.
     * {@code PluginConnection.stop()} synchronizes callbacks against its lifecycle
     * monitor, so a late OkHttp callback that sneaks in after reset() will see
     * stopped=true on the connection side and never reach apply().
     */
    public void reset() {
        rootGroup = null;
        open.clear();
    }
}
