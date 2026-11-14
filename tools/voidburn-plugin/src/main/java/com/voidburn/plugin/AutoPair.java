package com.voidburn.plugin;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for the auto-pair flow: extract a Voidburn session token from
 * the chat message the gateway sends after login, and validate an OSRS name
 * before writing it into config.
 *
 * Kept separate from {@link VoidburnPlugin} so the extraction is
 * unit-testable without a running RuneLite client or an event bus.
 */
final class AutoPair {
    private AutoPair() {}

    /**
     * Matches the token line the gateway emits in
     * {@code GatewaySession.handleLogin}:
     *
     *     "RuneLite plugin token: {@code <base64url with dashes>}"
     *
     * The token itself is 32 bytes of {@code SecureRandom} → URL-safe
     * base64 (no padding) → split into 4-char groups joined by '-'. Group
     * boundaries are deterministic, so the character class is just
     * {@code [A-Za-z0-9_-]+}. We anchor on the full prefix so a chat
     * message merely containing the word "token" doesn't match, and we
     * accept trailing whitespace the chat pipeline might append.
     */
    private static final Pattern TOKEN_LINE = Pattern.compile(
        "^RuneLite plugin token:\\s+([A-Za-z0-9_-]+(?:-[A-Za-z0-9_-]+)*)\\s*$");

    /**
     * Pull the token out of a chat message. Returns empty when the line
     * isn't the token line (every other game message), or when the
     * payload is empty.
     *
     * {@code message} should be the raw text of a {@code GAMEMESSAGE}
     * chat event. The Voidburn-authored "Paste this in Settings …"
     * follow-up line is ignored because it doesn't match the prefix.
     */
    static Optional<String> extractToken(String message) {
        if (message == null) return Optional.empty();
        Matcher m = TOKEN_LINE.matcher(message);
        if (!m.matches()) return Optional.empty();
        String token = m.group(1);
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /**
     * Decide whether to write {@code nameFromClient} to config. Returns
     * the name to write when auto-pair is on, the name is a valid OSRS
     * display name, and the stored config value is different; otherwise
     * empty.
     *
     * Used by VoidburnPlugin's both auto-pair entry points — the
     * {@code GameStateChanged(LOGGED_IN)} handler, and the
     * {@code ChatMessage(GAMEMESSAGE)} handler as a deferred retry. v1
     * of 2f-1 did the username capture only at LOGGED_IN; a null
     * {@code Client.getLocalPlayer()} at that tick meant the username
     * was never recovered. Centralizing the decision here lets both
     * events share the same rule.
     */
    static Optional<String> usernameToWrite(boolean autoPairOn,
                                            String nameFromClient,
                                            String currentConfigValue) {
        if (!autoPairOn) return Optional.empty();
        if (!isValidUsername(nameFromClient)) return Optional.empty();
        String current = currentConfigValue == null ? "" : currentConfigValue.trim();
        if (nameFromClient.equals(current)) return Optional.empty();
        return Optional.of(nameFromClient);
    }

    /**
     * Decide whether to write {@code chatMessage}'s token to config.
     * Combines {@link #extractToken} with the auto-pair-on and
     * different-from-current guards.
     */
    static Optional<String> tokenToWrite(boolean autoPairOn,
                                         String chatMessage,
                                         String currentConfigValue) {
        if (!autoPairOn) return Optional.empty();
        Optional<String> extracted = extractToken(chatMessage);
        if (extracted.isEmpty()) return Optional.empty();
        String current = currentConfigValue == null ? "" : currentConfigValue.trim();
        if (extracted.get().equals(current)) return Optional.empty();
        return extracted;
    }

    /**
     * Validate an OSRS display name before writing it into the plugin
     * config. OSRS names are 1–12 characters, ASCII letters, digits,
     * space, underscore and hyphen. Rejecting a name with any other
     * character keeps a malformed {@code getLocalPlayer().getName()}
     * (null, empty, or a leftover placeholder) from reaching the
     * gateway as an attach, where the parser would reject it and the
     * plugin would enter a reconnect loop.
     */
    static boolean isValidUsername(String name) {
        if (name == null) return false;
        int length = name.length();
        if (length < 1 || length > 12) return false;
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == ' ' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
