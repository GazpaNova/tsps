package com.voidburn.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the geometry and liveness of the mouse-listener's hit-test. v2
 * computes regions on each hitTest from current state and the last-render
 * viewport, so tests verify the LIVE behaviour (e.g. a widget_set_hidden
 * applied after the last render deactivates the URL immediately).
 */
class MotdOverlayHitTestTest {
    /** Component id 186 in group 30020. Mirrors Layout.MotdInterface.js. */
    private static final int PLAY_BUTTON_UID = (30020 << 16) | 186;

    /** Presentation with empty footer URL slots (Play-only hit regions). */
    private static final MotdPresentation EMPTY_FOOTERS = new MotdPresentation(
        "", "", List.of(), new MotdPresentation.Footer("", "", ""));

    /** Presentation carrying the three default footer labels with real URLs. */
    private static final MotdPresentation DEFAULT_FOOTERS = new MotdPresentation(
        "", "", List.of(),
        new MotdPresentation.Footer(
            "Discord: https://discord.gg/example",
            "Source: https://github.com/example/repo",
            "Launcher: https://voidburn.example/launcher"));

    // --- computeHitRegions geometry (pure static) ---

    @Test
    void play_button_is_at_panel_right_in_a_standard_canvas() {
        List<MotdOverlay.HitRegion> regions = MotdOverlay.computeHitRegions(765, 503, EMPTY_FOOTERS);
        assertEquals(1, regions.size(), "Play-only when no URL footers are present");
        MotdOverlay.HitRegion play = regions.get(0);
        MotdOverlay.HitAction.Click click = assertInstanceOf(MotdOverlay.HitAction.Click.class, play.action());
        assertEquals(PLAY_BUTTON_UID, click.widgetUid());
        assertEquals(1, click.op());
        assertEquals(new Rectangle(462, 427, 144, 28), play.bounds());
    }

    @Test
    void url_footers_populate_only_when_label_has_a_parseable_url() {
        List<MotdOverlay.HitRegion> withUrls =
            MotdOverlay.computeHitRegions(765, 503, DEFAULT_FOOTERS);
        assertEquals(4, withUrls.size(), "Play + three URL footers");
        assertInstanceOf(MotdOverlay.HitAction.Click.class, withUrls.get(0).action());
        assertUrlRegion(withUrls.get(1), "https://discord.gg/example",
            new Rectangle(158, 419, 296, 14));
        assertUrlRegion(withUrls.get(2), "https://github.com/example/repo",
            new Rectangle(158, 435, 296, 14));
        assertUrlRegion(withUrls.get(3), "https://voidburn.example/launcher",
            new Rectangle(158, 451, 296, 14));
    }

    @Test
    void partial_url_delivery_only_shows_the_delivered_ones() {
        MotdPresentation partial = new MotdPresentation(
            "", "", List.of(),
            new MotdPresentation.Footer(
                "Discord: https://discord.gg/example", "", ""));
        List<MotdOverlay.HitRegion> regions =
            MotdOverlay.computeHitRegions(765, 503, partial);
        assertEquals(2, regions.size(), "Play + one URL");
        assertInstanceOf(MotdOverlay.HitAction.Click.class, regions.get(0).action());
        assertInstanceOf(MotdOverlay.HitAction.OpenUrl.class, regions.get(1).action());
    }

    // --- hitTest against live state ---

    @Test
    void click_inside_play_button_hits_outside_misses() {
        InterfaceState state = withMotdRoot();
        MotdOverlay overlay = overlayWith(state);
        setLastViewport(overlay, 765, 503);

        assertTrue(overlay.hitTest(462, 427).isPresent(), "top-left corner hits");
        assertTrue(overlay.hitTest(462 + 72, 427 + 14).isPresent(), "center hits");
        assertFalse(overlay.hitTest(461, 427).isPresent(), "one px left misses");
        assertFalse(overlay.hitTest(462 + 144, 427).isPresent(), "one px right misses");
    }

    @Test
    void url_click_returns_open_url_action_with_extracted_url() {
        InterfaceState state = withMotdRoot();
        state.apply(new Frame.WidgetSetText(uid(180), "Discord: https://discord.gg/example"));
        MotdOverlay overlay = overlayWith(state);
        setLastViewport(overlay, 765, 503);

        Optional<MotdOverlay.HitRegion> hit = overlay.hitTest(200, 425);
        assertTrue(hit.isPresent());
        MotdOverlay.HitAction.OpenUrl url =
            assertInstanceOf(MotdOverlay.HitAction.OpenUrl.class, hit.get().action());
        assertEquals("https://discord.gg/example", url.url());
    }

    @Test
    void widget_set_hidden_deactivates_url_region_without_next_render() {
        // Regression (v1 review P2): v1 cached OpenUrl regions at render
        // time; a widget_set_hidden applied after the last render still
        // left the old URL action live. v2 rebuilds on every hitTest
        // from live state, so hiding the component now takes effect
        // between renders.
        InterfaceState state = withMotdRoot();
        state.apply(new Frame.WidgetSetText(uid(180), "Discord: https://discord.gg/example"));
        MotdOverlay overlay = overlayWith(state);
        setLastViewport(overlay, 765, 503);
        // Baseline: the URL hits.
        assertTrue(overlay.hitTest(200, 425).isPresent());

        // Server hides the component.
        state.apply(new Frame.WidgetSetHidden(uid(180), true));
        assertFalse(overlay.hitTest(200, 425).isPresent(),
            "hidden URL component must not re-hit, even before the next render");
        // Play button still works.
        assertTrue(overlay.hitTest(500, 440).isPresent());
    }

    @Test
    void widget_set_text_change_updates_url_without_next_render() {
        // Similar to the hidden case: the server updates the label text
        // to a different URL. Clicking the region returns the NEW URL,
        // not the one captured at render time.
        InterfaceState state = withMotdRoot();
        state.apply(new Frame.WidgetSetText(uid(180), "Discord: https://old.example"));
        MotdOverlay overlay = overlayWith(state);
        setLastViewport(overlay, 765, 503);
        MotdOverlay.HitAction.OpenUrl first = assertInstanceOf(
            MotdOverlay.HitAction.OpenUrl.class,
            overlay.hitTest(200, 425).orElseThrow().action());
        assertEquals("https://old.example", first.url());

        state.apply(new Frame.WidgetSetText(uid(180), "Discord: https://new.example"));
        MotdOverlay.HitAction.OpenUrl second = assertInstanceOf(
            MotdOverlay.HitAction.OpenUrl.class,
            overlay.hitTest(200, 425).orElseThrow().action());
        assertEquals("https://new.example", second.url(),
            "URL re-extracts from current state, not from the last render snapshot");
    }

    @Test
    void dismiss_disables_hit_test_before_next_render() {
        InterfaceState state = withMotdRoot();
        MotdOverlay overlay = overlayWith(state);
        setLastViewport(overlay, 765, 503);
        assertTrue(overlay.hitTest(500, 440).isPresent());
        overlay.dismiss();
        assertFalse(overlay.isShowing());
        assertFalse(overlay.hitTest(500, 440).isPresent());
    }

    @Test
    void root_swap_disables_hit_test_before_next_render() {
        InterfaceState state = withMotdRoot();
        MotdOverlay overlay = overlayWith(state);
        setLastViewport(overlay, 765, 503);
        assertTrue(overlay.hitTest(500, 440).isPresent());
        state.apply(new Frame.WidgetSetRoot(30021));
        assertFalse(overlay.hitTest(500, 440).isPresent());
    }

    @Test
    void reset_clears_viewport_so_pre_first_render_hits_miss() {
        InterfaceState state = withMotdRoot();
        MotdOverlay overlay = overlayWith(state);
        setLastViewport(overlay, 765, 503);
        overlay.dismiss();
        overlay.reset();
        assertFalse(overlay.hitTest(500, 440).isPresent(),
            "no viewport until the next render -> no hit");
    }

    @Test
    void hit_test_returns_empty_before_first_render() {
        InterfaceState state = withMotdRoot();
        MotdOverlay overlay = overlayWith(state);
        // No setLastViewport — overlay has never rendered.
        assertFalse(overlay.hitTest(0, 0).isPresent());
        assertFalse(overlay.hitTest(500, 440).isPresent());
    }

    @Test
    void very_small_canvas_still_reports_a_region() {
        List<MotdOverlay.HitRegion> regions = MotdOverlay.computeHitRegions(100, 100, EMPTY_FOOTERS);
        assertEquals(1, regions.size());
    }

    // --- helpers ---

    private static int uid(int component) {
        return (MotdPresentation.MOTD_GROUP_ID << 16) | component;
    }

    private static InterfaceState withMotdRoot() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MotdPresentation.MOTD_GROUP_ID));
        return state;
    }

    private static MotdOverlay overlayWith(InterfaceState state) {
        return new MotdOverlay(null, () -> Optional.of(state));
    }

    private static void assertUrlRegion(MotdOverlay.HitRegion region, String url,
                                        Rectangle bounds) {
        MotdOverlay.HitAction.OpenUrl open =
            assertInstanceOf(MotdOverlay.HitAction.OpenUrl.class, region.action());
        assertEquals(url, open.url());
        assertEquals(bounds, region.bounds());
    }

    /** Reflection poke: set the viewport the last render recorded. */
    private static void setLastViewport(MotdOverlay overlay, int w, int h) {
        try {
            java.lang.reflect.Field field = MotdOverlay.class.getDeclaredField("lastViewport");
            field.setAccessible(true);
            field.set(overlay, new Dimension(w, h));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("lastViewport field not reachable", e);
        }
    }
}
