package com.voidburn.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Player-facing configuration for the Voidburn plugin. RuneLite builds the
 * settings panel from these annotations: each @ConfigItem becomes a labelled
 * field, and the plugin listens on ConfigChanged to pick up edits and
 * reconnect.
 *
 * All three values are session-scoped and personal: the gateway URL points at
 * the player's own gateway, the username identifies which logged-in session to
 * attach to, and the token authorizes that attach. The token field is marked
 * {@code secret = true} so RuneLite renders a masked input and keeps it out of
 * plain-text logs.
 */
@ConfigGroup(VoidburnConfig.GROUP)
public interface VoidburnConfig extends Config {
    String GROUP = "voidburn";

    /** Key names referenced in code (writes via ConfigManager.setConfiguration). */
    String KEY_GATEWAY_URL = "gatewayUrl";
    String KEY_USERNAME = "username";
    String KEY_TOKEN = "token";
    String KEY_AUTO_PAIR = "autoPair";

    @ConfigSection(
        name = "Connection",
        description = "Where and how the plugin talks to the Voidburn gateway",
        position = 0
    )
    String connectionSection = "connection";

    @ConfigItem(
        keyName = KEY_GATEWAY_URL,
        name = "Gateway URL",
        description = "WebSocket URL of the Voidburn plugin listener, e.g. ws://127.0.0.1:8081/plugin",
        position = 1,
        section = connectionSection
    )
    default String gatewayUrl() {
        // Dev-friendly default. The Voidburn launcher will override this at
        // bundle time by writing the production gateway URL into
        // RuneLite's config on first launch. A player running the plugin
        // in vanilla RuneLite still edits this to point at wherever their
        // own gateway lives.
        return "ws://127.0.0.1:8081/plugin";
    }

    @ConfigItem(
        keyName = KEY_USERNAME,
        name = "OSRS username",
        description = "Your Voidburn display name — case-sensitive, exactly as it appears in game. "
            + "Left blank with auto-pair on, the plugin fills this from the logged-in player.",
        position = 2,
        section = connectionSection
    )
    default String username() {
        return "";
    }

    @ConfigItem(
        keyName = KEY_TOKEN,
        name = "Session token",
        description = "The token Voidburn messaged you after login: \"RuneLite plugin token: ...\". "
            + "With auto-pair on, the plugin captures this from the chat message itself; you only "
            + "need to enter it manually if you turned auto-pair off.",
        position = 3,
        section = connectionSection,
        secret = true
    )
    default String token() {
        return "";
    }

    @ConfigItem(
        keyName = KEY_AUTO_PAIR,
        name = "Auto-pair on login",
        description = "When enabled, the plugin fills Username from the logged-in player and "
            + "captures Session Token from the \"RuneLite plugin token: …\" chat message that "
            + "arrives after login. Disable this if you want to drive the pairing manually.",
        position = 4,
        section = connectionSection
    )
    default boolean autoPair() {
        return true;
    }
}
