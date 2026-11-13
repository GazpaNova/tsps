package com.voidburn.plugin;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
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

    private final Supplier<Optional<InterfaceState>> stateSupplier;
    private final Client client;
    private final AtomicBoolean dismissed = new AtomicBoolean(false);

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
     * Local dismiss. Called by the plugin when the player clicks the overlay's
     * "Close MOTD" right-click menu entry. The server's view of the interface
     * is unchanged — Phase 2d will route a real click back through the
     * gateway.
     */
    public void dismiss() {
        dismissed.set(true);
    }

    /** Re-arm MOTD display — called when a new connection starts. */
    public void reset() {
        dismissed.set(false);
    }

    /**
     * True iff the overlay should draw this tick. Factored out so tests can
     * exercise the visibility rule without a Graphics2D. The plugin also
     * uses this to skip right-click menu handling for a hidden overlay.
     */
    public boolean isShowing() {
        if (dismissed.get()) return false;
        Optional<InterfaceState> state = stateSupplier.get();
        return state.isPresent() && state.get().isOpen(MotdPresentation.MOTD_GROUP_ID);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!isShowing()) return null;
        Optional<MotdPresentation> maybe = stateSupplier.get().flatMap(MotdPresentation::from);
        if (maybe.isEmpty()) return null;
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

        return new Dimension(bounds.width, bounds.height);
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
