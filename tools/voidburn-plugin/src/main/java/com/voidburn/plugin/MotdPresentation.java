package com.voidburn.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure value layer between the plugin's {@link InterfaceState} and the
 * {@link MotdOverlay} that draws the MOTD panel. Extracting the layout here
 * means every decision about "which component id is the title, which bodies
 * go in which section, what the footer URLs are" is unit-testable without a
 * live RuneLite client or a Graphics2D harness.
 *
 * The component ids and the MOTD group id ({@value #MOTD_GROUP_ID}) are
 * pinned to the server's {@code server/plugins/interface/motd/
 * Layout.MotdInterface.js} layout. A server-side layout change that renames
 * or renumbers components must also update this file; the tests make that
 * link explicit.
 *
 * Immutability: every record copy-wraps its lists at construction so a caller
 * can safely hold a snapshot while a later {@link InterfaceState} update
 * replaces the backing Interface record.
 */
public record MotdPresentation(
    String title,
    String tagline,
    List<Section> sections,
    Footer footer
) {
    /** MOTD custom-interface group id. Must match Layout.MotdInterface.js. */
    public static final int MOTD_GROUP_ID = 30020;

    /** Component ids the server writes via sendString. See Layout.MotdInterface.js. */
    public static final int TITLE = 3;
    public static final int TAGLINE = 4;
    public static final int ABOUT_HEADING = 10;
    public static final int ABOUT_BODY = 11;
    public static final int RATES_HEADING = 20;
    public static final int CHANGES_HEADING = 160;
    public static final int CHANGES_BODY = 161;
    public static final int NEW_HEADING = 170;
    public static final int NEW_BODY = 171;
    public static final int FOOTER_DISCORD_LABEL = 180;
    public static final int FOOTER_GITHUB_LABEL = 181;
    public static final int FOOTER_LAUNCHER_LABEL = 182;

    public MotdPresentation {
        sections = List.copyOf(sections);
    }

    /**
     * A heading-and-body text pair. Either side can be empty — the renderer
     * draws only the non-empty parts, so an unused section (e.g. no body
     * text ever landed) still renders its heading alone.
     */
    public record Section(String heading, String body) {}

    /** The three footer URLs the server sends as plain-text labels. */
    public record Footer(String discord, String github, String launcher) {}

    /**
     * Build a presentation from the currently-open MOTD interface. Returns
     * empty when the server hasn't opened group {@link #MOTD_GROUP_ID} yet
     * — the overlay interprets that as "don't render."
     *
     * Each text field reads from the component table; null (not seen) and
     * absent (no component entry) both collapse to the empty string here, so
     * the renderer treats them uniformly as "nothing to draw yet." Phase
     * 2c-3b stores null for "server hasn't told us"; by the time the overlay
     * cares, either the server has told us or we show a blank line.
     */
    public static Optional<MotdPresentation> from(InterfaceState state) {
        return state.get(MOTD_GROUP_ID).map(MotdPresentation::from);
    }

    /** Package-private variant used directly in tests that skip the InterfaceState hop. */
    static MotdPresentation from(InterfaceState.Interface motd) {
        List<Section> sections = new ArrayList<>(4);
        // ABOUT section headlined by ABOUT_HEADING with ABOUT_BODY as body.
        sections.add(new Section(text(motd, ABOUT_HEADING), text(motd, ABOUT_BODY)));
        // RATES: heading only for now (the rows are sprites + tier labels not
        // reachable through text updates; the renderer can display heading as
        // a signpost until a richer rendering lands).
        sections.add(new Section(text(motd, RATES_HEADING), ""));
        sections.add(new Section(text(motd, CHANGES_HEADING), text(motd, CHANGES_BODY)));
        sections.add(new Section(text(motd, NEW_HEADING), text(motd, NEW_BODY)));
        return new MotdPresentation(
            text(motd, TITLE),
            text(motd, TAGLINE),
            sections,
            new Footer(
                text(motd, FOOTER_DISCORD_LABEL),
                text(motd, FOOTER_GITHUB_LABEL),
                text(motd, FOOTER_LAUNCHER_LABEL)
            )
        );
    }

    /**
     * Read the server-provided text for a component, honoring an explicit
     * widget_set_hidden=true by projecting to the empty string.
     *
     * Null-hidden (never received) and explicit false stay visible: that
     * preserves 2c-2b's null/false distinction for a renderer that cares
     * (nothing here does yet), while explicit true suppresses the stored
     * text exactly as the server intended. Storage is unchanged — a later
     * hidden=false still reveals the original text because the Component
     * record kept it.
     */
    private static String text(InterfaceState.Interface motd, int componentId) {
        InterfaceState.Component c = motd.components().get(componentId);
        if (c == null || c.text() == null) return "";
        if (Boolean.TRUE.equals(c.hidden())) return "";
        return c.text();
    }
}
