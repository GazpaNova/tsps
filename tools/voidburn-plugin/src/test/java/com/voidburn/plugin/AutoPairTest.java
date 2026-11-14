package com.voidburn.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AutoPairTest {

    // --- token extraction ---

    @Test
    void extract_pulls_token_from_gateway_chat_message() {
        // Matches the exact line GatewaySession.handleLogin emits:
        //   session.queue(MessageGame(GAME_MESSAGE,
        //     "RuneLite plugin token: $token"))
        // The token is 32 bytes of SecureRandom → base64url (no padding) →
        // chunked(4).joinToString("-") so it only contains [A-Za-z0-9_-].
        assertEquals(Optional.of("abcd-EFGH-1234-_-__"),
            AutoPair.extractToken("RuneLite plugin token: abcd-EFGH-1234-_-__"));
    }

    @Test
    void extract_accepts_trailing_whitespace() {
        // Some chat pipelines append a trailing space; the regex tolerates it.
        assertEquals(Optional.of("TOKEN-abcd"),
            AutoPair.extractToken("RuneLite plugin token: TOKEN-abcd "));
        assertEquals(Optional.of("TOKEN-abcd"),
            AutoPair.extractToken("RuneLite plugin token: TOKEN-abcd  \t"));
    }

    @Test
    void extract_rejects_the_follow_up_line() {
        // The gateway emits two lines after mint; only the first is the token.
        assertFalse(AutoPair.extractToken(
            "Paste this in Settings → Voidburn in RuneLite to pair the plugin.").isPresent());
    }

    @Test
    void extract_rejects_unrelated_game_messages() {
        assertFalse(AutoPair.extractToken("Welcome to Voidburn.").isPresent());
        assertFalse(AutoPair.extractToken("You have been poisoned!").isPresent());
        assertFalse(AutoPair.extractToken("").isPresent());
        assertFalse(AutoPair.extractToken(null).isPresent());
    }

    @Test
    void extract_rejects_token_embedded_in_a_longer_sentence() {
        // Only the strict prefix matches, so a player can't poison a captured
        // token by typing it in a public chat line.
        assertFalse(AutoPair.extractToken(
            "Did you see this: RuneLite plugin token: SOMETHING").isPresent());
        assertFalse(AutoPair.extractToken(
            "fake RuneLite plugin token: SOMETHING").isPresent());
    }

    @Test
    void extract_rejects_missing_or_malformed_payload() {
        assertFalse(AutoPair.extractToken("RuneLite plugin token: ").isPresent());
        assertFalse(AutoPair.extractToken("RuneLite plugin token:").isPresent());
        // Space inside the token isn't produced by the mint; rejected.
        assertFalse(AutoPair.extractToken(
            "RuneLite plugin token: part1 part2").isPresent());
        // A character outside the base64url + dash set means a malformed line.
        assertFalse(AutoPair.extractToken(
            "RuneLite plugin token: ILLEGAL/CHARS").isPresent());
    }

    // --- username validation ---

    @Test
    void isValidUsername_accepts_normal_osrs_names() {
        assertTrue(AutoPair.isValidUsername("Miguel"));
        assertTrue(AutoPair.isValidUsername("a"));                 // 1 char
        assertTrue(AutoPair.isValidUsername("Zezima"));
        assertTrue(AutoPair.isValidUsername("mod-ash"));           // hyphen
        assertTrue(AutoPair.isValidUsername("foo_bar"));           // underscore
        assertTrue(AutoPair.isValidUsername("twelve chars"));      // 12 chars, space
    }

    // --- usernameToWrite decision ---

    @Test
    void usernameToWrite_returns_name_when_auto_pair_on_and_differs_from_current() {
        assertEquals(Optional.of("Gazpa"),
            AutoPair.usernameToWrite(true, "Gazpa", ""));
        assertEquals(Optional.of("Gazpa"),
            AutoPair.usernameToWrite(true, "Gazpa", "Miguel"));
        // Whitespace in the current value is tolerated (config can be trimmed).
        assertEquals(Optional.of("Gazpa"),
            AutoPair.usernameToWrite(true, "Gazpa", "  "));
    }

    @Test
    void usernameToWrite_empty_when_auto_pair_off() {
        assertFalse(AutoPair.usernameToWrite(false, "Gazpa", "").isPresent());
    }

    @Test
    void usernameToWrite_empty_when_name_is_invalid() {
        assertFalse(AutoPair.usernameToWrite(true, null, "").isPresent());
        assertFalse(AutoPair.usernameToWrite(true, "", "").isPresent());
        assertFalse(AutoPair.usernameToWrite(true, "<null>", "").isPresent());
    }

    @Test
    void usernameToWrite_empty_when_matches_current() {
        assertFalse(AutoPair.usernameToWrite(true, "Gazpa", "Gazpa").isPresent());
    }

    // --- tokenToWrite decision ---

    @Test
    void tokenToWrite_returns_token_when_auto_pair_on_and_differs() {
        assertEquals(Optional.of("abcd-EFGH"),
            AutoPair.tokenToWrite(true, "RuneLite plugin token: abcd-EFGH", ""));
        assertEquals(Optional.of("abcd-EFGH"),
            AutoPair.tokenToWrite(true, "RuneLite plugin token: abcd-EFGH", "old-token"));
    }

    @Test
    void tokenToWrite_empty_when_auto_pair_off() {
        assertFalse(AutoPair.tokenToWrite(false, "RuneLite plugin token: abcd", "").isPresent());
    }

    @Test
    void tokenToWrite_empty_when_message_has_no_token() {
        assertFalse(AutoPair.tokenToWrite(true, "Welcome to Voidburn.", "").isPresent());
    }

    @Test
    void tokenToWrite_empty_when_matches_current() {
        assertFalse(AutoPair.tokenToWrite(true, "RuneLite plugin token: abcd", "abcd").isPresent());
    }

    // --- regression: v1 review P2 ---

    @Test
    void p2_token_arrival_still_captures_username_when_login_missed_it() {
        // Scenario: LOGGED_IN fired with getLocalPlayer()==null, so no
        // username write happened. Later, the token chat message arrives
        // and by now the Client has a local player named "Gazpa". v2 calls
        // tryFillUsername() from the chat handler too; the decision
        // function must return the name to write.
        //
        // This test pins the decision shape; the plugin wires it up by
        // calling usernameToWrite at exactly these two event points.
        assertEquals(Optional.of("Gazpa"),
            AutoPair.usernameToWrite(true, "Gazpa", ""),
            "chat handler re-attempt must return the name when config is still empty");
    }

    @Test
    void isValidUsername_rejects_junk() {
        assertFalse(AutoPair.isValidUsername(null));
        assertFalse(AutoPair.isValidUsername(""));
        assertFalse(AutoPair.isValidUsername("thirteenchars"));    // 13 chars
        assertFalse(AutoPair.isValidUsername("bad\tname"));        // tab
        assertFalse(AutoPair.isValidUsername("bad\nname"));        // newline
        assertFalse(AutoPair.isValidUsername("em💥oji"));          // emoji
        assertFalse(AutoPair.isValidUsername("\"quoted\""));       // quote
        // A leading <, often used by RuneLite for dead/placeholder names.
        assertFalse(AutoPair.isValidUsername("<null>"));
    }
}
