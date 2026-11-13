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
    void shownWhenMotdOpen() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(MOTD_GROUP_ID, true));
        assertTrue(overlay(state).isShowing());
    }

    @Test
    void shownAfterSetRoot() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MOTD_GROUP_ID));
        assertTrue(overlay(state).isShowing());
    }

    @Test
    void notShownForUnrelatedCustomGroup() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(30007, true));
        assertFalse(overlay(state).isShowing(),
            "a different custom interface must not steal the MOTD overlay");
    }

    @Test
    void dismissSuppressesUntilReset() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(MOTD_GROUP_ID, true));
        MotdOverlay o = overlay(state);
        assertTrue(o.isShowing());
        o.dismiss();
        assertFalse(o.isShowing(), "dismiss() hides even while the server still has MOTD open");
        o.reset();
        assertTrue(o.isShowing(), "reset() re-arms visibility for a new session");
    }

    @Test
    void hiddenAfterInterfaceStateReset() {
        // The plugin resets InterfaceState when a connection retires. Overlay
        // visibility follows state → no MOTD entry means no draw.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(MOTD_GROUP_ID, true));
        MotdOverlay o = overlay(state);
        assertTrue(o.isShowing());
        state.reset();
        assertFalse(o.isShowing());
    }
}
