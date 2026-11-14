package com.voidburn.plugin;

import java.awt.Desktop;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens URLs extracted from MOTD footer labels in the player's default
 * browser. Separate from the mouse plumbing so the URL-extraction and
 * Desktop-open paths can be unit-tested in isolation, and so a future
 * server-pushed URLs map can replace {@link #extract} without touching
 * the overlay or the plugin.
 *
 * <p>URL extraction: grabs the first {@code http://} or {@code https://}
 * substring (everything up to the next whitespace) in a label. The MOTD
 * server authors footer labels as {@code "Discord: https://discord.gg/…"}
 * (MotdInterface.plugin.js, {@code readFooterLabels}), so a strict URL
 * prefix and a non-whitespace tail match the format without a false
 * positive on a leading label word. If no URL is in the string,
 * {@link #extract} returns empty and the caller skips the open attempt.
 *
 * <p>Open: tries {@link Desktop#browse} first and logs on any failure
 * (unsupported platform, malformed URI, user-browser launch failure). A
 * failed open never throws out of this class — a dead click is better
 * than a crashed plugin thread.
 */
final class UrlOpener {
    private static final Logger log = LoggerFactory.getLogger(UrlOpener.class);

    /**
     * Matches a URL from the start of http:// or https:// through the next
     * whitespace or end-of-string. Intentionally strict on the scheme and
     * permissive on the tail; false positives need a literal {@code http://}
     * inside the label, which the shipping defaults don't produce.
     */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final Opener opener;

    /** Default-constructed opener uses java.awt.Desktop; tests inject a mock. */
    UrlOpener() {
        this(UrlOpener::browseViaDesktop);
    }

    UrlOpener(Opener opener) {
        this.opener = opener;
    }

    /**
     * Extract the URL from a label such as {@code "Discord: https://…"}.
     * Returns empty when the label is null or contains no URL.
     */
    static Optional<String> extract(String label) {
        if (label == null) return Optional.empty();
        Matcher m = URL_PATTERN.matcher(label);
        if (!m.find()) return Optional.empty();
        String url = m.group();
        // Trim a trailing punctuation character that a human reader might
        // write but that is almost never part of the URL itself: period,
        // comma, closing bracket. Keeps label text like "see https://x.y/."
        // from opening "https://x.y/." which some browsers refuse.
        while (!url.isEmpty()) {
            char last = url.charAt(url.length() - 1);
            if (last == '.' || last == ',' || last == ')' || last == ']' || last == '>') {
                url = url.substring(0, url.length() - 1);
            } else {
                break;
            }
        }
        return url.isEmpty() ? Optional.empty() : Optional.of(url);
    }

    /**
     * Open {@code url} in the player's default browser. Returns true when
     * the open was handed off successfully, false when it was refused or
     * threw. Never throws.
     *
     * Safety: validates http/https + a non-empty host at this boundary,
     * so even if a caller bypasses {@link #extract} and hands this
     * method a {@code file:} or {@code javascript:} URL, it is rejected
     * here before reaching the injected opener. {@code extract} already
     * refuses non-http(s); this is defence in depth (v1 review P3).
     */
    boolean open(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            String lowerScheme = scheme.toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
                log.warn("Voidburn plugin: refusing to open non-web URL scheme: {}", scheme);
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                log.warn("Voidburn plugin: refusing to open URL with no host: {}", url);
                return false;
            }
            return opener.browse(uri);
        } catch (URISyntaxException e) {
            log.warn("Voidburn plugin: refusing to open malformed URL: {}", url, e);
            return false;
        } catch (RuntimeException e) {
            log.warn("Voidburn plugin: browser open threw for {}", url, e);
            return false;
        }
    }

    /** Test seam: a function that opens a URI and reports success/failure. */
    @FunctionalInterface
    interface Opener {
        boolean browse(URI uri);
    }

    private static boolean browseViaDesktop(URI uri) {
        if (!Desktop.isDesktopSupported()) {
            log.debug("Voidburn plugin: no Desktop support on this JVM; cannot open {}", uri);
            return false;
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            log.debug("Voidburn plugin: Desktop.BROWSE unsupported; cannot open {}", uri);
            return false;
        }
        try {
            desktop.browse(uri);
            return true;
        } catch (java.io.IOException e) {
            log.warn("Voidburn plugin: Desktop.browse failed for {}", uri, e);
            return false;
        }
    }
}
