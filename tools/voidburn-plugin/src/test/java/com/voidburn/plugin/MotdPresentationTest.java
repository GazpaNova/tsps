package com.voidburn.plugin;

import static com.voidburn.plugin.MotdPresentation.ABOUT_BODY;
import static com.voidburn.plugin.MotdPresentation.ABOUT_HEADING;
import static com.voidburn.plugin.MotdPresentation.CHANGES_BODY;
import static com.voidburn.plugin.MotdPresentation.CHANGES_HEADING;
import static com.voidburn.plugin.MotdPresentation.FOOTER_DISCORD_LABEL;
import static com.voidburn.plugin.MotdPresentation.FOOTER_GITHUB_LABEL;
import static com.voidburn.plugin.MotdPresentation.FOOTER_LAUNCHER_LABEL;
import static com.voidburn.plugin.MotdPresentation.MOTD_GROUP_ID;
import static com.voidburn.plugin.MotdPresentation.NEW_BODY;
import static com.voidburn.plugin.MotdPresentation.NEW_HEADING;
import static com.voidburn.plugin.MotdPresentation.RATES_HEADING;
import static com.voidburn.plugin.MotdPresentation.TAGLINE;
import static com.voidburn.plugin.MotdPresentation.TITLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MotdPresentation is where the plugin decides which stored component ids
 * map to which panel section. Server-side layout changes have to land here
 * too; these tests make the link explicit so a future rename can't silently
 * paint blank sections.
 */
class MotdPresentationTest {

    private static int uid(int componentId) {
        return (MOTD_GROUP_ID << 16) | componentId;
    }

    @Test
    void emptyStateReturnsEmpty() {
        InterfaceState state = new InterfaceState();
        assertTrue(MotdPresentation.from(state).isEmpty());
    }

    @Test
    void openInterfaceWithNoTextYieldsEmptyStrings() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(MOTD_GROUP_ID, true));
        MotdPresentation p = MotdPresentation.from(state).orElseThrow();
        assertEquals("", p.title());
        assertEquals("", p.tagline());
        for (MotdPresentation.Section s : p.sections()) {
            assertEquals("", s.heading());
            assertEquals("", s.body());
        }
        assertEquals("", p.footer().discord());
        assertEquals("", p.footer().github());
        assertEquals("", p.footer().launcher());
    }

    @Test
    void sendStringsLandInTheExpectedSlots() {
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(MOTD_GROUP_ID, true));
        state.apply(new Frame.WidgetSetText(uid(TITLE), "Welcome back, Miguel!"));
        state.apply(new Frame.WidgetSetText(uid(TAGLINE), "Current server news."));
        state.apply(new Frame.WidgetSetText(uid(ABOUT_HEADING), "About Voidburn"));
        state.apply(new Frame.WidgetSetText(uid(ABOUT_BODY), "A fresh take on OSRS."));
        state.apply(new Frame.WidgetSetText(uid(RATES_HEADING), "XP rates"));
        state.apply(new Frame.WidgetSetText(uid(CHANGES_HEADING), "Our changes"));
        state.apply(new Frame.WidgetSetText(uid(CHANGES_BODY), "No prayer book."));
        state.apply(new Frame.WidgetSetText(uid(NEW_HEADING), "What's new"));
        state.apply(new Frame.WidgetSetText(uid(NEW_BODY), "Sailing is in."));
        state.apply(new Frame.WidgetSetText(uid(FOOTER_DISCORD_LABEL), "discord.gg/voidburn"));
        state.apply(new Frame.WidgetSetText(uid(FOOTER_GITHUB_LABEL), "github.com/voidburn"));
        state.apply(new Frame.WidgetSetText(uid(FOOTER_LAUNCHER_LABEL), "voidburn.net/launcher"));

        MotdPresentation p = MotdPresentation.from(state).orElseThrow();
        assertEquals("Welcome back, Miguel!", p.title());
        assertEquals("Current server news.", p.tagline());
        List<MotdPresentation.Section> ss = p.sections();
        assertEquals(4, ss.size());
        assertEquals(new MotdPresentation.Section("About Voidburn", "A fresh take on OSRS."), ss.get(0));
        assertEquals(new MotdPresentation.Section("XP rates", ""), ss.get(1));
        assertEquals(new MotdPresentation.Section("Our changes", "No prayer book."), ss.get(2));
        assertEquals(new MotdPresentation.Section("What's new", "Sailing is in."), ss.get(3));
        assertEquals(new MotdPresentation.Footer(
            "discord.gg/voidburn",
            "github.com/voidburn",
            "voidburn.net/launcher"
        ), p.footer());
    }

    @Test
    void laterSendStringOverwritesEarlierSendString() {
        // The server may re-emit a title (news ticker, etc.). The presentation
        // reflects the latest value.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetOpen(MOTD_GROUP_ID, true));
        state.apply(new Frame.WidgetSetText(uid(TITLE), "first"));
        state.apply(new Frame.WidgetSetText(uid(TITLE), "second"));
        assertEquals("second", MotdPresentation.from(state).orElseThrow().title());
    }

    @Test
    void explicitHiddenTrueSuppressesTextInPresentation() {
        // Regression (v1 review P2-b): v1 ignored Component.hidden, so a
        // server-sent widget_set_hidden=true left the text visible in the
        // overlay. v2 projects hidden=true to the empty string in the
        // presentation; the stored component text is unchanged so a later
        // hidden=false reveals it again.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MOTD_GROUP_ID));
        state.apply(new Frame.WidgetSetText(uid(TITLE), "Hidden title"));
        state.apply(new Frame.WidgetSetHidden(uid(TITLE), true));
        assertEquals("", MotdPresentation.from(state).orElseThrow().title(),
            "hidden=true must suppress the title in the presentation");

        // Re-reveal: hidden=false brings the stored text back.
        state.apply(new Frame.WidgetSetHidden(uid(TITLE), false));
        assertEquals("Hidden title", MotdPresentation.from(state).orElseThrow().title(),
            "hidden=false must restore the stored text");
    }

    @Test
    void nullHiddenDoesNotSuppressText() {
        // Null hidden (never received) keeps the visibility default — treat
        // as not hidden. Only explicit true suppresses.
        InterfaceState state = new InterfaceState();
        state.apply(new Frame.WidgetSetRoot(MOTD_GROUP_ID));
        state.apply(new Frame.WidgetSetText(uid(TITLE), "shown"));
        assertEquals("shown", MotdPresentation.from(state).orElseThrow().title());
    }

    @Test
    void sectionsAreImmutableAfterConstruction() {
        MotdPresentation p = new MotdPresentation(
            "t", "tg",
            new java.util.ArrayList<>(List.of(new MotdPresentation.Section("h", "b"))),
            new MotdPresentation.Footer("d", "g", "l"));
        try {
            p.sections().clear();
            org.junit.jupiter.api.Assertions.fail("sections should be an unmodifiable copy");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
