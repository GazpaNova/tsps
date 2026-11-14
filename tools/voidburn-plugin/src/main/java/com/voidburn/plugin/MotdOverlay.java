package com.voidburn.plugin;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Renders the Voidburn MOTD as a fullscreen-covering panel over RuneLite's
 * game canvas. Reads {@link InterfaceState} on every render tick (client
 * thread) and extracts a {@link MotdPresentation} from the MOTD group the
 * server opened.
 *
 * Shows when:
 *  - the plugin's InterfaceState has an open entry for MOTD_GROUP_ID, AND
 *  - the player hasn't dismissed this session (right-click → Close MOTD).
 *
 * Dismissal is purely local. The server still considers the interface open;
 * Phase 2d will route an actual click back to the server so it closes
 * authoritatively. Dismissed is cleared when the plugin stops the
 * underlying connection (new login → new session → new chance to see MOTD).
 *
 * Layout (not pixel-perfect to the browser version — a canvas-style render
 * is a later phase): semi-transparent scrim across the whole viewport,
 * centered 480x460-ish panel with Voidburn-ish palette, title, tagline,
 * each Section's heading-and-body drawn stacked, then footer links. Font
 * is Graphics2D's Java default; RuneLite's FontManager fonts would match
 * the plugin aesthetic more closely, but adding them is a style patch, not
 * a milestone one.
 */
public class MotdOverlay extends Overlay {
    /** Dark scrim color for the viewport-fill behind the panel. */
    private static final Color SCRIM = new Color(0, 0, 0, 180);

    /** Panel background and chrome colors. Approximate Voidburn palette. */
    private static final Color PANEL_BG = new Color(0x14, 0x10, 0x0b);
    private static final Color PANEL_BORDER = new Color(0x3a, 0x31, 0x25);
    private static final Color TITLE_COLOR = new Color(0xd4, 0xb8, 0x1b);
    private static final Color BODY_COLOR = new Color(0xe8, 0xde, 0xd0);
    private static final Color MUTED_COLOR = new Color(0xb8, 0xab, 0x94);

    private static final int PANEL_WIDTH = 480;
    private static final int PANEL_HEIGHT = 460;
    private static final int PANEL_PADDING = 20;

    /**
     * Play-button layout pinned to Layout.MotdInterface.js (component 186).
     * Width 144, height 28, positioned at the far right of the footer with
     * a 16-px gutter from the panel's right edge and the same vertical
     * anchor as the server-side layer (y = 406 from panel top).
     */
    private static final int PLAY_BUTTON_WIDTH = 144;
    private static final int PLAY_BUTTON_HEIGHT = 28;
    private static final int PLAY_BUTTON_RIGHT_PADDING = 16;
    private static final int PLAY_BUTTON_Y_FROM_PANEL_TOP = 406;

    /** Component id and op for the Play button. Mirrors Layout.MotdInterface.js. */
    private static final int PLAY_BUTTON_COMPONENT_ID = 186;
    private static final int PLAY_BUTTON_OP = 1;
    private static final int PLAY_BUTTON_UID =
        (MotdPresentation.MOTD_GROUP_ID << 16) | PLAY_BUTTON_COMPONENT_ID;

    /**
     * Footer URL layout pinned to Layout.MotdInterface.js:
     *   - panel-local x=16, width=URL_W (panel.width - 32 - PLAY_W - 8 = 280)
     *   - y-coordinates 398 / 414 / 430, each 14 tall
     *
     * The three ids match MotdPresentation.FOOTER_*_LABEL exactly so the
     * hit test can look the URL up via MotdPresentation.
     */
    private static final int URL_FOOTER_X = 16;
    private static final int URL_FOOTER_WIDTH = PANEL_WIDTH - 32 - PLAY_BUTTON_WIDTH - 8;
    private static final int URL_FOOTER_HEIGHT = 14;
    private static final int URL_FOOTER_DISCORD_Y = 398;
    private static final int URL_FOOTER_GITHUB_Y = 414;
    private static final int URL_FOOTER_LAUNCHER_Y = 430;

    /** Voidburn purple for the Play button fill; gold for its label. */
    private static final Color PLAY_BUTTON_FILL = new Color(0x6b, 0x16, 0xe8, 160);
    private static final Color PLAY_BUTTON_BORDER = new Color(0xd4, 0xb8, 0x1b);
    private static final Color PLAY_BUTTON_LABEL = new Color(0xd4, 0xb8, 0x1b);

    /** Text drawn in the Play button. */
    private static final String PLAY_BUTTON_LABEL_TEXT = "Enter Voidburn";

    private final Supplier<Optional<InterfaceState>> stateSupplier;
    private final Client client;
    private final AtomicBoolean dismissed = new AtomicBoolean(false);

    /**
     * Viewport size recorded by the most recent {@link #render}. Read by
     * the AWT event thread from {@link #hitTest} to recompute regions
     * against the LIVE {@link InterfaceState} / {@link MotdPresentation},
     * so a hit never fires against a region that no longer exists
     * (component hidden since last render, text rewritten, overlay
     * dismissed, root swapped).
     *
     * Volatile reference to an immutable {@link Dimension}; null until
     * the first render.
     */
    private volatile Dimension lastViewport = null;

    /**
     * A screen-space rectangle attached to an action. The action decides
     * what the mouse listener does on a hit: {@link HitAction.Click} sends
     * an upstream click frame (the Play button), {@link HitAction.OpenUrl}
     * opens the URL in the player's browser (the three footer labels —
     * same behaviour as the TypeScript client's CustomInterfaceRuntime,
     * no server round-trip).
     */
    public record HitRegion(Rectangle bounds, HitAction action) {
        public HitRegion {
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(action, "action");
        }
    }

    /**
     * What the mouse listener does when a hit region is clicked. A sealed
     * interface keeps the dispatch branches structural rather than
     * nullable-field switches: a {@code Click} carries exactly the ids
     * {@link ClickFrame} needs, an {@code OpenUrl} carries exactly the
     * URL {@link UrlOpener} needs.
     */
    public sealed interface HitAction permits HitAction.Click, HitAction.OpenUrl {
        /** Send a plugin → gateway click frame (goes to tsps for the Play button). */
        record Click(int widgetUid, int op) implements HitAction {}

        /** Open this URL in the player's browser (handled client-side on the plugin). */
        record OpenUrl(String url) implements HitAction {
            public OpenUrl {
                Objects.requireNonNull(url, "url");
            }
        }
    }

    public MotdOverlay(Client client, Supplier<Optional<InterfaceState>> stateSupplier) {
        this.client = client;
        this.stateSupplier = stateSupplier;
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.HIGH);
        getMenuEntries().add(new OverlayMenuEntry(
            net.runelite.api.MenuAction.RUNELITE_OVERLAY,
            "Close MOTD",
            "Voidburn"));
    }

    /**
     * Local dismiss. Called by the plugin when the player picks the overlay's
     * "Close MOTD" right-click menu entry.
     *
     * RuneLite 1.13.3 note: overlay menu entries only appear when the player
     * holds Shift while right-clicking (pinned OverlayRenderer behavior). So
     * the real UX is "Shift + right-click on the panel → Close MOTD".
     * Players can also disable the plugin from the RuneLite settings. A
     * Shift-less dismiss path would need the plugin to register its own
     * mouse adapter — left for a later patch.
     *
     * The server's view of the interface is unchanged — Phase 2d will route
     * a real click back through the gateway.
     */
    public void dismiss() {
        dismissed.set(true);
        // isShowing() returns false immediately; hitTest honours that on
        // its next call. No cached regions to clear in v2's rebuild-on-
        // demand model.
    }

    /** Re-arm MOTD display — called when a new connection starts. */
    public void reset() {
        dismissed.set(false);
        // Viewport snapshot is cleared so a hit before the first post-reset
        // render has no size to recompute against, which keeps the
        // transition symmetric even though isShowing() would also refuse.
        lastViewport = null;
    }

    /**
     * True iff the overlay should draw this tick. Driven by the CURRENT custom
     * root: MOTD is only visible while the server's active root is
     * MOTD_GROUP_ID (set via the server's sendRootInterface, which the gateway
     * forwards as widget_set_root). A later set_root to a different custom
     * interface rotates rootGroup and hides this overlay automatically —
     * regression from v1, which checked isOpen() alone and kept MOTD painted
     * on top after a swap.
     *
     * Note on stock-root transitions: the accepted gateway only forwards
     * set_root for custom groups (>= 30000). If the server rotates MOTD →
     * stock (e.g. the player clicks the Play button and the server calls
     * sendRootInterface(161)), the plugin won't see that today and the
     * overlay stays visible until a new custom root lands or the player
     * dismisses. A gateway-side extension to forward stock set_roots to an
     * attached plugin is a later phase; the local dismiss path covers the
     * gap in the meantime.
     */
    public boolean isShowing() {
        if (dismissed.get()) return false;
        Optional<InterfaceState> state = stateSupplier.get();
        if (state.isEmpty()) return false;
        Optional<Integer> root = state.get().rootGroup();
        return root.isPresent() && root.get() == MotdPresentation.MOTD_GROUP_ID;
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!isShowing()) {
            lastViewport = null;
            return null;
        }
        Optional<MotdPresentation> maybe = stateSupplier.get().flatMap(MotdPresentation::from);
        if (maybe.isEmpty()) {
            lastViewport = null;
            return null;
        }
        MotdPresentation p = maybe.get();

        Rectangle bounds = client.getCanvas().getBounds();
        lastViewport = new Dimension(bounds.width, bounds.height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Scrim covers the entire viewport.
        g.setColor(SCRIM);
        g.fillRect(0, 0, bounds.width, bounds.height);

        // Panel centered on the viewport.
        int px = Math.max(0, (bounds.width - PANEL_WIDTH) / 2);
        int py = Math.max(0, (bounds.height - PANEL_HEIGHT) / 2);
        int pw = Math.min(PANEL_WIDTH, bounds.width);
        int ph = Math.min(PANEL_HEIGHT, bounds.height);
        g.setColor(PANEL_BG);
        g.fillRect(px, py, pw, ph);
        g.setColor(PANEL_BORDER);
        g.drawRect(px, py, pw - 1, ph - 1);

        int contentX = px + PANEL_PADDING;
        int contentW = pw - PANEL_PADDING * 2;
        int y = py + PANEL_PADDING + 14;

        y = drawLine(g, p.title(), contentX, y, contentW, TITLE_COLOR, 18, true);
        y = drawLine(g, p.tagline(), contentX, y + 6, contentW, MUTED_COLOR, 12, false);
        y += 10;
        for (MotdPresentation.Section s : p.sections()) {
            if (!s.heading().isEmpty()) {
                y = drawLine(g, s.heading(), contentX, y, contentW, TITLE_COLOR, 13, true);
            }
            if (!s.body().isEmpty()) {
                y = drawWrapped(g, s.body(), contentX, y + 2, contentW, BODY_COLOR, 12);
            }
            y += 8;
        }
        // Footer URLs: draw at the EXACT panel-relative coordinates
        // computeHitRegions uses for the hit rectangles (x=16, width=
        // URL_FOOTER_WIDTH, fixed rows at y=398/414/430 inside the panel,
        // height 14). Clipped to the URL region so a long label can't
        // stretch into the Play button's column. v1 drew via drawLine
        // with contentX/contentW and row spacing from FontMetrics, so
        // the painted lines did not match the hit rectangles — v1
        // review P2.
        drawFooterUrl(g, px, py, URL_FOOTER_DISCORD_Y, p.footer().discord());
        drawFooterUrl(g, px, py, URL_FOOTER_GITHUB_Y, p.footer().github());
        drawFooterUrl(g, px, py, URL_FOOTER_LAUNCHER_Y, p.footer().launcher());

        // Play button. Drawing uses the same coordinates computeHitRegions
        // reports, so what gets painted is exactly what will be clickable.
        List<HitRegion> regions = computeHitRegions(bounds.width, bounds.height, p);
        for (HitRegion region : regions) {
            if (region.action() instanceof HitAction.Click click
                    && click.widgetUid() == PLAY_BUTTON_UID) {
                Rectangle b = region.bounds();
                drawPlayButton(g, b.x, b.y);
            }
        }

        return new Dimension(bounds.width, bounds.height);
    }

    /**
     * Draw one footer URL label at its fixed panel-relative row, clipped
     * to the URL region's width. Matches the hit rectangle byte-for-byte
     * so a click on the painted text always lands on the region and
     * vice-versa.
     */
    private void drawFooterUrl(Graphics2D g, int px, int py,
                               int footerYInPanel, String label) {
        if (label == null || label.isEmpty()) return;
        g.setFont(g.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
        g.setColor(MUTED_COLOR);
        FontMetrics fm = g.getFontMetrics();
        String clipped = clip(label, fm, URL_FOOTER_WIDTH);
        // Vertically center the ascender inside the fixed-height row so
        // the text sits where a reader expects it rather than stacking
        // the baseline at the row top.
        int baselineY = py + footerYInPanel + fm.getAscent()
            + (URL_FOOTER_HEIGHT - fm.getHeight()) / 2;
        g.drawString(clipped, px + URL_FOOTER_X, baselineY);
    }

    /**
     * Compute the clickable hit regions for a viewport of the given size.
     * Pure function — called from {@link #render} on the client thread and
     * directly from unit tests.
     *
     * The panel is centered on the viewport using the same math {@link #render}
     * uses; the Play button is anchored to the panel's top-left exactly as
     * {@code Layout.MotdInterface.js} declares. URL footer regions are
     * attached to the server-provided label text via
     * {@link UrlOpener#extract}: a footer label with no parseable URL
     * yields no hit region (so a click lands on nothing until the server
     * text catches up).
     */
    static List<HitRegion> computeHitRegions(int viewportWidth, int viewportHeight,
                                             MotdPresentation presentation) {
        int px = Math.max(0, (viewportWidth - PANEL_WIDTH) / 2);
        int py = Math.max(0, (viewportHeight - PANEL_HEIGHT) / 2);
        int pw = Math.min(PANEL_WIDTH, viewportWidth);

        List<HitRegion> out = new ArrayList<>(4);

        // Play button.
        int playX = px + pw - PLAY_BUTTON_WIDTH - PLAY_BUTTON_RIGHT_PADDING;
        int playY = py + PLAY_BUTTON_Y_FROM_PANEL_TOP;
        out.add(new HitRegion(
            new Rectangle(playX, playY, PLAY_BUTTON_WIDTH, PLAY_BUTTON_HEIGHT),
            new HitAction.Click(PLAY_BUTTON_UID, PLAY_BUTTON_OP)));

        // URL footers. Each footer is only clickable when the server has
        // delivered a label that contains a parseable URL — otherwise the
        // click should have no effect (the label is empty or in some
        // intermediate pre-sendString state).
        addUrlRegion(out, px, py, URL_FOOTER_DISCORD_Y, presentation.footer().discord());
        addUrlRegion(out, px, py, URL_FOOTER_GITHUB_Y, presentation.footer().github());
        addUrlRegion(out, px, py, URL_FOOTER_LAUNCHER_Y, presentation.footer().launcher());

        return List.copyOf(out);
    }

    private static void addUrlRegion(List<HitRegion> out, int px, int py,
                                     int footerYInPanel, String label) {
        Optional<String> url = UrlOpener.extract(label);
        if (url.isEmpty()) return;
        out.add(new HitRegion(
            new Rectangle(
                px + URL_FOOTER_X, py + footerYInPanel,
                URL_FOOTER_WIDTH, URL_FOOTER_HEIGHT),
            new HitAction.OpenUrl(url.get())));
    }

    /**
     * Draw the "Enter Voidburn" button at the given screen coordinates.
     * Rendering is independent of the hit region: a reader of
     * {@link #hitRegions} gets the exact bounds that were painted.
     */
    private void drawPlayButton(Graphics2D g, int x, int y) {
        g.setColor(PLAY_BUTTON_FILL);
        g.fillRect(x, y, PLAY_BUTTON_WIDTH, PLAY_BUTTON_HEIGHT);
        g.setColor(PLAY_BUTTON_BORDER);
        g.drawRect(x, y, PLAY_BUTTON_WIDTH - 1, PLAY_BUTTON_HEIGHT - 1);
        g.setColor(PLAY_BUTTON_LABEL);
        g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(PLAY_BUTTON_LABEL_TEXT);
        int tx = x + (PLAY_BUTTON_WIDTH - textWidth) / 2;
        int ty = y + (PLAY_BUTTON_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(PLAY_BUTTON_LABEL_TEXT, tx, ty);
    }

    /**
     * Hit-test an AWT canvas-space click. Returns the matching region on
     * a hit, or empty when the point is outside every clickable area.
     * Safe to call from the AWT event thread.
     *
     * Rebuild-on-demand: unlike v1/v2 which cached regions published by
     * render, this reads the CURRENT {@link InterfaceState} (via the
     * state supplier) and recomputes regions against the last viewport
     * seen by render. That makes the test fresh against per-component
     * state too — if {@code widget_set_hidden} retired a URL label since
     * the last render, {@link MotdPresentation#from} projects it to the
     * empty string, {@link UrlOpener#extract} returns empty, and that
     * URL region vanishes immediately. v1 review P2 was that OpenUrl
     * actions captured at render time could outlive the component they
     * came from until the next render.
     *
     * Visibility gate: {@link #isShowing} and a known viewport are both
     * required. Dismissal, root swap, session reset, and
     * pre-first-render all short-circuit to empty.
     */
    public Optional<HitRegion> hitTest(int x, int y) {
        if (!isShowing()) return Optional.empty();
        Dimension viewport = lastViewport;
        if (viewport == null) return Optional.empty();
        Optional<MotdPresentation> maybe = stateSupplier.get().flatMap(MotdPresentation::from);
        if (maybe.isEmpty()) return Optional.empty();
        for (HitRegion region : computeHitRegions(viewport.width, viewport.height, maybe.get())) {
            if (region.bounds().contains(x, y)) {
                return Optional.of(region);
            }
        }
        return Optional.empty();
    }

    private int drawLine(Graphics2D g, String text, int x, int y, int maxWidth,
                         Color color, int fontSize, boolean bold) {
        if (text == null || text.isEmpty()) return y;
        g.setFont(g.getFont().deriveFont(bold ? java.awt.Font.BOLD : java.awt.Font.PLAIN,
                                         (float) fontSize));
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        String clipped = clip(text, fm, maxWidth);
        g.drawString(clipped, x, y + fm.getAscent());
        return y + fm.getHeight();
    }

    private int drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth,
                            Color color, int fontSize) {
        g.setFont(g.getFont().deriveFont(java.awt.Font.PLAIN, (float) fontSize));
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        int cursorY = y;
        for (String line : wrap(text, fm, maxWidth)) {
            g.drawString(line, x, cursorY + fm.getAscent());
            cursorY += fm.getHeight();
        }
        return cursorY;
    }

    /** Best-effort clip for single-line fields; ends with "..." if the full text won't fit. */
    private String clip(String text, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ellipsisW = fm.stringWidth(ellipsis);
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisW > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    /**
     * Word-wrap a body paragraph to fit in maxWidth. Splits on ASCII space;
     * does not attempt a CJK-aware break. A word longer than maxWidth is
     * placed on its own line and left to overflow — the body is author-
     * controlled on the server, so long tokens are rare.
     */
    private List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> out = new ArrayList<>();
        // Hard-break on embedded newlines so a server-authored paragraph
        // break survives the wrap.
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (fm.stringWidth(candidate) > maxWidth && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            out.add(line.toString());
        }
        return out;
    }
}
