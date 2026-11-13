package com.voidburn.plugin;

import static com.voidburn.plugin.MotdPresentation.MOTD_GROUP_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The overlay's {@link MotdOverlay#isShowing()} rule decides whether a render
 * runs at all — tests on it don't need a Graphics2D. Visual fidelity of the
 * actual draw is a visual-review concern (what Codex would eyeball during a
 * live session); here we pin the show/hide logic.
 *
 * MotdOverlay's constructor references RuneLite's Client, but isShowing()
 * doesn't; we pass null for the client in these tests.
 */
class MotdOverlayVisibilityTest {

    private MotdOverlay overlay(InterfaceState state) {
        return new MotdOverlay(null, () -> Optional.of(state));
    }

    @Test
    void hiddenWhenNoInterfaceOpen() {
        InterfaceState state = new InterfaceState();
        assertFalse(overlay(state).isShowing(), "no MOTD opened → hidden");
    }

    @Test
    void shownAfterServerSetsMotdAsRoot() {
        // The server opens MOTD via sendRootInterface(30020), forwarded as
        // widget_set_root. isShowing's visibility is driven by the active
        // root — not just whether group 30020 has an entry — so this is the
        // canonical path.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MOTD_GROUP_ID));
        assertTrue(overlay(state).isShowing());
    }

    @Test
    void notShownForWidgetOpenAlone() {
        // widget_open creates an entry in the open-set but doesn't move
        // rootGroup. isShowing is root-driven, so this must stay hidden —
        // otherwise a non-root modal would steal the MOTD overlay.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(MOTD_GROUP_ID, true));
        assertFalse(overlay(state).isShowing(),
            "widget_open alone (no set_root) must not show MOTD");
    }

    @Test
    void notShownForUnrelatedCustomGroup() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(30007));
        assertFalse(overlay(state).isShowing(),
            "a different custom root must not trigger the MOTD overlay");
    }

    @Test
    void hiddenAfterServerRotatesToDifferentCustomRoot() {
        // Regression (v1 review P2-a): v1 checked isOpen(30020), which stays
        // true after rootGroup moves to another custom group. The scrim
        // then painted over the newly-active interface. v2 is root-driven so
        // the swap hides MOTD immediately.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MOTD_GROUP_ID));
        assertTrue(overlay(state).isShowing());
        state.apply(new Frame.WidgetSetRoot(30021));
        assertFalse(overlay(state).isShowing(),
            "root-swap to another custom group must hide MOTD");
    }

    @Test
    void dismissSuppressesUntilReset() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MOTD_GROUP_ID));
        MotdOverlay o = overlay(state);
        assertTrue(o.isShowing());
        o.dismiss();
        assertFalse(o.isShowing(), "dismiss() hides even while the server still has MOTD open");
        o.reset();
        assertTrue(o.isShowing(), "reset() re-arms visibility for a new session");
    }

    @Test
    void hiddenAfterInterfaceStateReset() {
        // The plugin resets InterfaceState when a connection retires. rootGroup
        // clears alongside the open map → no custom root → no draw.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MOTD_GROUP_ID));
        MotdOverlay o = overlay(state);
        assertTrue(o.isShowing());
        state.reset();
        assertFalse(o.isShowing());
    }
}
