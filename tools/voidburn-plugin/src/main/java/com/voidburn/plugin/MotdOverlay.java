package com.voidburn.plugin;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Collections;
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
     * Hit regions published by the most recent {@link #render} on the client
     * thread, read by the AWT event thread from {@link #hitTest}. Volatile
     * reference + immutable {@link List#copyOf} snapshot = a reader always
     * sees either the previous render's regions or the new render's
     * regions, never a half-constructed list.
     *
     * Empty when the overlay isn't currently showing (render() short-circuits
     * before publishing regions in that case) or when no clickable widget
     * was drawn this frame.
     */
    private volatile List<HitRegion> hitRegions = Collections.emptyList();

    /**
     * A screen-space rectangle attached to a server component. On a mouse
     * click inside {@link #bounds}, the plugin sends a click frame carrying
     * {@link #widgetUid} and {@link #op} upstream.
     */
    public record HitRegion(Rectangle bounds, int widgetUid, int op) {
        public HitRegion {
            Objects.requireNonNull(bounds, "bounds");
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
        // Clear published hit regions immediately so a second click that
        // arrives before the next render can't re-activate the retired
        // button. hitTest also guards on isShowing() — belt and suspenders,
        // but both paths independently cover the "state change outruns the
        // next render" window.
        hitRegions = Collections.emptyList();
    }

    /** Re-arm MOTD display — called when a new connection starts. */
    public void reset() {
        dismissed.set(false);
        // Same reasoning as dismiss(): the previous session's regions must
        // not survive into the new one. The next render will re-publish for
        // the fresh session once a widget_set_root lands.
        hitRegions = Collections.emptyList();
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
            hitRegions = Collections.emptyList();
            return null;
        }
        Optional<MotdPresentation> maybe = stateSupplier.get().flatMap(MotdPresentation::from);
        if (maybe.isEmpty()) {
            hitRegions = Collections.emptyList();
            return null;
        }
        MotdPresentation p = maybe.get();

        Rectangle bounds = client.getCanvas().getBounds();
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
        // Footer: three URLs on their own lines.
        int footerY = py + ph - PANEL_PADDING - 3 * 14;
        y = Math.max(y, footerY);
        y = drawLine(g, p.footer().discord(), contentX, y, contentW, MUTED_COLOR, 11, false);
        y = drawLine(g, p.footer().github(), contentX, y, contentW, MUTED_COLOR, 11, false);
        drawLine(g, p.footer().launcher(), contentX, y, contentW, MUTED_COLOR, 11, false);

        // Play button. Drawing uses the same coordinates computeHitRegions
        // reports, so what gets painted is exactly what will be clickable.
        List<HitRegion> regions = computeHitRegions(bounds.width, bounds.height);
        for (HitRegion region : regions) {
            if (region.widgetUid() == PLAY_BUTTON_UID) {
                Rectangle b = region.bounds();
                drawPlayButton(g, b.x, b.y);
            }
        }
        hitRegions = regions;

        return new Dimension(bounds.width, bounds.height);
    }

    /**
     * Compute the clickable hit regions for a viewport of the given size.
     * Pure function — called from {@link #render} on the client thread and
     * directly from unit tests that reason about geometry without a
     * {@link Graphics2D}.
     *
     * The panel is centered on the viewport using the same math {@link #render}
     * uses; the Play button is anchored to the panel's top-left exactly as
     * {@code Layout.MotdInterface.js} declares (panel-space
     * x = {@value #PANEL_WIDTH} - {@value #PLAY_BUTTON_WIDTH} -
     * {@value #PLAY_BUTTON_RIGHT_PADDING},
     * y = {@value #PLAY_BUTTON_Y_FROM_PANEL_TOP}).
     */
    static List<HitRegion> computeHitRegions(int viewportWidth, int viewportHeight) {
        int px = Math.max(0, (viewportWidth - PANEL_WIDTH) / 2);
        int py = Math.max(0, (viewportHeight - PANEL_HEIGHT) / 2);
        int pw = Math.min(PANEL_WIDTH, viewportWidth);
        int playX = px + pw - PLAY_BUTTON_WIDTH - PLAY_BUTTON_RIGHT_PADDING;
        int playY = py + PLAY_BUTTON_Y_FROM_PANEL_TOP;
        return List.of(new HitRegion(
            new Rectangle(playX, playY, PLAY_BUTTON_WIDTH, PLAY_BUTTON_HEIGHT),
            PLAY_BUTTON_UID,
            PLAY_BUTTON_OP));
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
     * Hit-test an AWT canvas-space click against the currently published
     * regions. Returns the matching region on a hit, or empty when the
     * point is outside every clickable area. Safe to call from the AWT
     * event thread.
     *
     * Visibility gate: hit-testing always consults {@link #isShowing}
     * first. The published regions snapshot only refreshes at render
     * cadence, so a state change that flips the overlay off (local
     * dismiss, a server-driven root swap to a different group, or a
     * connection reset) must deactivate regions immediately rather than
     * wait for the next render. Otherwise a second click arriving in
     * that window double-sends, and a click after a root swap sends to
     * a no-longer-visible interface (v1 review P2).
     */
    public Optional<HitRegion> hitTest(int x, int y) {
        if (!isShowing()) return Optional.empty();
        for (HitRegion region : hitRegions) {
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
