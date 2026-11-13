package com.voidburn.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * State-transition tests for InterfaceState. Each test applies a short sequence of
 * frames and asserts the state shape afterwards. Threading is covered by the state's
 * backing ConcurrentHashMap and immutable records; these tests focus on semantic
 * correctness, not thread safety.
 */
class InterfaceStateTest {

    @Test
    void initiallyEmpty() {
        InterfaceState state = new InterfaceState();
        assertTrue(state.rootGroup().isEmpty());
        assertFalse(state.isOpen(30000));
    }

    @Test
    void widgetOpenAddsInterface() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30000, true));
        assertTrue(state.isOpen(30000));
        InterfaceState.Interface open = state.get(30000).orElseThrow();
        assertEquals(30000, open.group());
        assertTrue(open.modal());
        assertTrue(open.subMounts().isEmpty());
    }

    @Test
    void widgetOpenNonModalRecordsModalFalse() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30007, false));
        assertFalse(state.get(30007).orElseThrow().modal());
    }

    @Test
    void secondWidgetOpenUpdatesModalFlagInPlace() {
        // The gateway may send widget_open twice for the same group with a different
        // modal flag (unusual but allowed). The later value wins; sub-mounts persist.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30000, true));
        state.apply(new Frame.WidgetOpenSub(1, (30000 << 16) | 10, 30001, 0, List.of()));
        state.apply(new Frame.WidgetOpen(30000, false));
        InterfaceState.Interface open = state.get(30000).orElseThrow();
        assertFalse(open.modal());
        assertEquals(1, open.subMounts().size());
    }

    @Test
    void widgetSetRootRecordsRootAndEnsuresInterfaceEntry() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(30000));
        assertEquals(30000, state.rootGroup().orElseThrow());
        // A set_root without a prior widget_open still produces an Interface entry so
        // callers can query it.
        assertTrue(state.isOpen(30000));
    }

    @Test
    void widgetSetRootAfterOpenKeepsOpenModalFlag() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30000, false));
        state.apply(new Frame.WidgetSetRoot(30000));
        assertEquals(30000, state.rootGroup().orElseThrow());
        // The open came first with modal=false; set_root doesn't touch modal.
        assertFalse(state.get(30000).orElseThrow().modal());
    }

    @Test
    void widgetSetRootToDifferentGroupUpdatesRoot() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(30000));
        state.apply(new Frame.WidgetSetRoot(30020));
        assertEquals(30020, state.rootGroup().orElseThrow());
        // Both groups remain in the open map; the old one isn't auto-closed by root
        // rotation. Explicit widget_close (future frame) will handle removal.
        assertTrue(state.isOpen(30000));
        assertTrue(state.isOpen(30020));
    }

    @Test
    void widgetOpenSubAttachesToParent() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30000, true));
        int target = (30000 << 16) | 42;
        state.apply(new Frame.WidgetOpenSub(1234, target, 30001, 0, List.of(100, 200)));
        InterfaceState.Interface parent = state.get(30000).orElseThrow();
        assertEquals(1, parent.subMounts().size());
        InterfaceState.SubMount mount = parent.subMounts().get(target);
        assertEquals(1234, mount.rawTarget());
        assertEquals(target, mount.target());
        assertEquals(30001, mount.group());
        assertEquals(0, mount.interfaceType());
        assertEquals(java.util.Set.of(100, 200), mount.hiddenUids());
    }

    @Test
    void widgetOpenSubBeforeParentOpenCreatesPlaceholderParent() {
        // Robustness: if the server batches widget_open_sub before widget_open (or we
        // reconnect mid-stream), the sub still has a home. The parent is created with
        // modal=true as a reasonable default.
        InterfaceState state = new InterfaceState();
        int target = (30000 << 16) | 10;
        state.apply(new Frame.WidgetOpenSub(1, target, 30001, 0, List.of()));
        assertTrue(state.isOpen(30000));
        InterfaceState.Interface parent = state.get(30000).orElseThrow();
        assertEquals(1, parent.subMounts().size());
    }

    @Test
    void twoSubMountsBelowOneParent() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30000, true));
        int t1 = (30000 << 16) | 10;
        int t2 = (30000 << 16) | 11;
        state.apply(new Frame.WidgetOpenSub(1, t1, 30001, 0, List.of()));
        state.apply(new Frame.WidgetOpenSub(2, t2, 30002, 0, List.of()));
        InterfaceState.Interface parent = state.get(30000).orElseThrow();
        assertEquals(2, parent.subMounts().size());
        assertEquals(30001, parent.subMounts().get(t1).group());
        assertEquals(30002, parent.subMounts().get(t2).group());
    }

    @Test
    void resubmittingSameTargetReplacesSub() {
        // If the same target is mounted twice, the second one wins. Matches the
        // server behavior where a re-open would overwrite the previous sub.
        InterfaceState state = new InterfaceState();
        int target = (30000 << 16) | 10;
        state.apply(new Frame.WidgetOpenSub(1, target, 30001, 0, List.of(100)));
        state.apply(new Frame.WidgetOpenSub(2, target, 30002, 1, List.of(200)));
        InterfaceState.SubMount mount = state.get(30000).orElseThrow().subMounts().get(target);
        assertEquals(30002, mount.group());
        assertEquals(1, mount.interfaceType());
        assertEquals(java.util.Set.of(200), mount.hiddenUids());
    }

    @Test
    void unknownFrameIsNoOp() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.Unknown("widget_set_text", "{\"type\":\"widget_set_text\"}"));
        assertTrue(state.rootGroup().isEmpty());
        assertFalse(state.isOpen(30000));
    }

    @Test
    void interfaceRecordIsImmutableAfterConstruction() {
        // The record copies its subMounts map, so a caller cannot mutate internal
        // state by holding a reference to the original map it passed in.
        java.util.HashMap<Integer, InterfaceState.SubMount> original = new java.util.HashMap<>();
        original.put(1, new InterfaceState.SubMount(0, 1, 30000, 0, java.util.Set.of()));
        InterfaceState.Interface i = new InterfaceState.Interface(30000, true, original);
        original.clear();
        assertEquals(1, i.subMounts().size(), "Interface subMounts must be a defensive copy");
    }

    @Test
    void getReturnsEmptyForUnknownGroup() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30000, true));
        assertTrue(state.get(30007).isEmpty());
    }

    // --- Phase 2c-2b review regressions ---

    @Test
    void resetClearsRootAndAllOpenInterfaces() {
        // Regression (P2-2): a retired session must not leak its root and mounts
        // into the next session's state.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30000, true));
        state.apply(new Frame.WidgetSetRoot(30000));
        state.apply(new Frame.WidgetOpenSub(1, (30000 << 16) | 10, 30001, 0, List.of(100)));
        state.reset();
        assertTrue(state.rootGroup().isEmpty());
        assertFalse(state.isOpen(30000));
        assertTrue(state.get(30000).isEmpty());
    }

    @Test
    void resetIsIdempotent() {
        InterfaceState state = new InterfaceState();
        state.reset();
        state.reset();
        assertTrue(state.rootGroup().isEmpty());
    }

    @Test
    void stateAcceptsNewFramesAfterReset() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30000, true));
        state.reset();
        state.apply(new Frame.WidgetOpen(30020, false));
        assertTrue(state.isOpen(30020));
        assertFalse(state.isOpen(30000));
    }

    @Test
    void widgetSetRootPublishesEntryBeforeRoot() {
        // Regression (P2-3): populating open[] before publishing rootGroup is now
        // explicit in apply(). This test can't deterministically reproduce the
        // store-ordering race in-process (JMM doesn't give us a cheap way to
        // inspect the interleaving), so it asserts the post-condition invariant
        // instead: after apply() returns, both the entry and the root are visible
        // together.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(30020));
        assertEquals(30020, state.rootGroup().orElseThrow());
        assertTrue(state.get(30020).isPresent());
    }
}
