package com.voidburn.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UrlOpenerTest {
    @Test
    void extract_pulls_url_from_labeled_footer() {
        // Shape from MotdInterface.plugin.js readFooterLabels:
        //   "Discord: https://discord.gg/foo"
        assertEquals(Optional.of("https://discord.gg/foo"),
            UrlOpener.extract("Discord: https://discord.gg/foo"));
        assertEquals(Optional.of("https://github.com/example/repo"),
            UrlOpener.extract("Source: https://github.com/example/repo"));
        assertEquals(Optional.of("https://voidburn.example/launcher"),
            UrlOpener.extract("Launcher: https://voidburn.example/launcher"));
    }

    @Test
    void extract_accepts_http_and_https() {
        assertEquals(Optional.of("http://example.com"),
            UrlOpener.extract("prefix http://example.com"));
        assertEquals(Optional.of("https://example.com"),
            UrlOpener.extract("prefix https://example.com"));
    }

    @Test
    void extract_trims_trailing_punctuation() {
        // Human-authored labels sometimes end in a period or closing
        // bracket. Browsers refuse "https://x.y/." so we trim.
        assertEquals(Optional.of("https://x.y/path"),
            UrlOpener.extract("see https://x.y/path."));
        assertEquals(Optional.of("https://x.y/path"),
            UrlOpener.extract("see https://x.y/path,"));
        assertEquals(Optional.of("https://x.y/path"),
            UrlOpener.extract("link (https://x.y/path)"));
    }

    @Test
    void extract_returns_empty_on_no_url() {
        assertFalse(UrlOpener.extract("plain text").isPresent());
        assertFalse(UrlOpener.extract("").isPresent());
        assertFalse(UrlOpener.extract(null).isPresent());
        assertFalse(UrlOpener.extract("Discord: (not yet set)").isPresent());
    }

    @Test
    void extract_ignores_non_http_schemes() {
        // We only forward public web URLs; a label that somehow contained
        // ftp:// or file:// is not what the browser action is for.
        assertFalse(UrlOpener.extract("ftp://example.com/x").isPresent());
        assertFalse(UrlOpener.extract("file:///etc/passwd").isPresent());
        assertFalse(UrlOpener.extract("javascript:alert(1)").isPresent());
    }

    @Test
    void open_hands_url_to_the_opener() {
        AtomicReference<URI> received = new AtomicReference<>();
        UrlOpener opener = new UrlOpener(uri -> { received.set(uri); return true; });
        assertTrue(opener.open("https://discord.gg/example"));
        assertEquals(URI.create("https://discord.gg/example"), received.get());
    }

    @Test
    void open_returns_false_on_malformed_url_without_throwing() {
        AtomicReference<URI> received = new AtomicReference<>();
        UrlOpener opener = new UrlOpener(uri -> { received.set(uri); return true; });
        // A space inside a URI is a URISyntaxException — the open path
        // must swallow it and never reach the browser. extract() wouldn't
        // produce this, but open() is also called in isolation.
        assertFalse(opener.open("https://example .com"));
        // Opener was not invoked.
        assertEquals(null, received.get());
    }

    @Test
    void open_returns_false_when_opener_refuses() {
        UrlOpener opener = new UrlOpener(uri -> false);
        assertFalse(opener.open("https://example.com"));
    }

    @Test
    void open_returns_false_when_opener_throws() {
        UrlOpener opener = new UrlOpener(uri -> { throw new RuntimeException("boom"); });
        assertFalse(opener.open("https://example.com"));
    }

    @Test
    void open_returns_false_on_null_or_empty() {
        UrlOpener opener = new UrlOpener(uri -> { throw new AssertionError("should not fire"); });
        assertFalse(opener.open(null));
        assertFalse(opener.open(""));
    }

    @Test
    void open_rejects_non_http_schemes_at_the_boundary() {
        // v1 review P3: open constructed a URI and delegated without
        // validating the scheme. extract() already filters non-http(s),
        // but open is the final safety boundary and must independently
        // refuse non-web schemes.
        UrlOpener opener = new UrlOpener(uri -> { throw new AssertionError(
            "non-http scheme must never reach the opener: " + uri); });
        assertFalse(opener.open("file:///etc/passwd"));
        assertFalse(opener.open("javascript:alert(1)"));
        assertFalse(opener.open("ftp://example.com/x"));
        assertFalse(opener.open("mailto:someone@example.com"));
    }

    @Test
    void open_rejects_urls_with_no_host() {
        // Scheme is http but no authority — not a real target.
        UrlOpener opener = new UrlOpener(uri -> { throw new AssertionError(
            "no-host URL must never reach the opener: " + uri); });
        assertFalse(opener.open("http:"));
        assertFalse(opener.open("http:///path"));
    }
}
