package com.voidburn.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the geometry the mouse listener consults: for a given viewport size,
 * the Play button sits at a known screen-space rect, and {@link
 * MotdOverlay#computeHitRegions} returns exactly that rect tagged with the
 * server's Play-button widget uid and op.
 *
 * Avoids the live {@link java.awt.Graphics2D} path entirely: the draw code
 * uses the same {@code computeHitRegions} output the tests consume, so what
 * is painted is exactly what will be clickable.
 */
class MotdOverlayHitTestTest {
    /** Component id 186 in group 30020. Mirrors Layout.MotdInterface.js. */
    private static final int PLAY_BUTTON_UID = (30020 << 16) | 186;

    @Test
    void play_button_is_at_panel_right_in_a_standard_canvas() {
        // RuneLite's resizable canvas is ~765x503 by default. The panel is
        // 480x460 centered; the Play button sits at panel-local x = 480-144-16
        // = 320, y = 406, with a 16-px right gutter and 28 px tall.
        List<MotdOverlay.HitRegion> regions = MotdOverlay.computeHitRegions(765, 503);
        assertEquals(1, regions.size(), "only Play button is clickable in 2d-2");
        MotdOverlay.HitRegion play = regions.get(0);
        assertEquals(PLAY_BUTTON_UID, play.widgetUid());
        assertEquals(1, play.op());
        Rectangle b = play.bounds();
        // Panel origin: ((765-480)/2, (503-460)/2) = (142, 21).
        // Play button origin: (142 + 320, 21 + 406) = (462, 427).
        assertEquals(new Rectangle(462, 427, 144, 28), b);
    }

    @Test
    void click_inside_button_hits_play_click_outside_misses() {
        // Same canvas as above. Shadow the geometry through a real MotdOverlay
        // instance so hitTest matches computeHitRegions. v2's hitTest also
        // gates on isShowing(), so the state supplier must carry the MOTD
        // root for hit-testing to be active at all.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MotdPresentation.MOTD_GROUP_ID));
        MotdOverlay overlay = new MotdOverlay(null, () -> java.util.Optional.of(state));
        List<MotdOverlay.HitRegion> regions = MotdOverlay.computeHitRegions(765, 503);
        // Publish regions directly — in production render() does this on the
        // client thread; here we don't exercise render().
        setHitRegions(overlay, regions);

        // Inside the button.
        assertTrue(overlay.hitTest(462, 427).isPresent(), "top-left corner hits");
        assertTrue(overlay.hitTest(462 + 72, 427 + 14).isPresent(), "center hits");
        assertTrue(overlay.hitTest(462 + 143, 427 + 27).isPresent(), "bottom-right inside");

        // Outside the button.
        assertFalse(overlay.hitTest(0, 0).isPresent(), "canvas corner misses");
        assertFalse(overlay.hitTest(461, 427).isPresent(), "one px left misses");
        assertFalse(overlay.hitTest(462 + 144, 427).isPresent(), "one px right of right edge misses");
        assertFalse(overlay.hitTest(462, 427 + 28).isPresent(), "one px below bottom misses");
    }

    @Test
    void empty_regions_means_every_hit_misses() {
        MotdOverlay overlay = new MotdOverlay(null, java.util.Optional::empty);
        // Overlay starts with an empty region list (before the first render).
        assertFalse(overlay.hitTest(0, 0).isPresent());
        assertFalse(overlay.hitTest(400, 300).isPresent());
    }

    @Test
    void dismiss_deactivates_regions_before_next_render() {
        // Regression (v1 review P2): after a Play click dismisses the
        // overlay locally, a second click arriving before the next render
        // must NOT re-match the stale published regions. hitTest's
        // isShowing() gate plus dismiss()'s clear-on-exit cover this
        // whether or not the next render has run.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MotdPresentation.MOTD_GROUP_ID));
        MotdOverlay overlay = new MotdOverlay(null, () -> java.util.Optional.of(state));
        // Simulate a prior render having published the Play button region.
        setHitRegions(overlay, MotdOverlay.computeHitRegions(765, 503));
        assertTrue(overlay.isShowing());
        assertTrue(overlay.hitTest(500, 440).isPresent(), "first click hits");

        overlay.dismiss();
        assertFalse(overlay.isShowing());
        assertFalse(overlay.hitTest(500, 440).isPresent(),
            "stale Play region must not re-hit after dismiss, even before render re-runs");
    }

    @Test
    void root_swap_deactivates_regions_before_next_render() {
        // Regression (v1 review P2): the server rotating the custom root to
        // a different custom group retires the MOTD panel. The published
        // regions are last-render-stale until the next render runs; hitTest
        // must honour the current root rather than the stale snapshot.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MotdPresentation.MOTD_GROUP_ID));
        MotdOverlay overlay = new MotdOverlay(null, () -> java.util.Optional.of(state));
        setHitRegions(overlay, MotdOverlay.computeHitRegions(765, 503));
        assertTrue(overlay.hitTest(500, 440).isPresent());

        // Server-side root rotation to a different custom interface.
        state.apply(new Frame.WidgetSetRoot(30021));
        assertFalse(overlay.isShowing(), "root is no longer MOTD");
        assertFalse(overlay.hitTest(500, 440).isPresent(),
            "stale Play region must not re-hit after root swap");
    }

    @Test
    void reset_clears_regions_and_rearms_without_dispatching_stale_clicks() {
        // openConnection on a new session calls reset() to clear the local
        // dismissed flag. The old session's regions must not survive that
        // transition. isShowing() will be false until the new session's
        // widget_set_root lands anyway (interfaceState.reset() cleared the
        // root), but reset() also drops regions directly as defence.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MotdPresentation.MOTD_GROUP_ID));
        MotdOverlay overlay = new MotdOverlay(null, () -> java.util.Optional.of(state));
        setHitRegions(overlay, MotdOverlay.computeHitRegions(765, 503));
        overlay.dismiss();
        assertFalse(overlay.hitTest(500, 440).isPresent());
        // Simulate openConnection: state wiped, dismissed cleared.
        state.reset();
        overlay.reset();
        assertFalse(overlay.isShowing(), "no root yet");
        assertFalse(overlay.hitTest(500, 440).isPresent(),
            "stale regions must not outlive a session reset");
    }

    @Test
    void very_small_canvas_still_reports_a_region() {
        // The panel clamps to a non-negative origin; on a 100x100 canvas the
        // Play button would clip off-screen, but the hit region is still
        // reported — the mouse listener only fires on in-canvas clicks, so
        // an off-canvas click is rejected by AWT rather than by the hit test.
        List<MotdOverlay.HitRegion> regions = MotdOverlay.computeHitRegions(100, 100);
        assertEquals(1, regions.size());
    }

    /**
     * Reflection poke to publish regions without running render(). The field
     * is intentionally private — tests cross the boundary here rather than
     * widening production visibility.
     */
    private static void setHitRegions(MotdOverlay overlay, List<MotdOverlay.HitRegion> regions) {
        try {
            java.lang.reflect.Field field = MotdOverlay.class.getDeclaredField("hitRegions");
            field.setAccessible(true);
            field.set(overlay, regions);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("hitRegions field not reachable", e);
        }
    }
}
