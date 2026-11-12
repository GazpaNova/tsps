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

    @ConfigSection(
        name = "Connection",
        description = "Where and how the plugin talks to the Voidburn gateway",
        position = 0
    )
    String connectionSection = "connection";

    @ConfigItem(
        keyName = "gatewayUrl",
        name = "Gateway URL",
        description = "WebSocket URL of the Voidburn plugin listener, e.g. ws://127.0.0.1:8081/plugin",
        position = 1,
        section = connectionSection
    )
    default String gatewayUrl() {
        return "";
    }

    @ConfigItem(
        keyName = "username",
        name = "OSRS username",
        description = "Your Voidburn display name — case-sensitive, exactly as it appears in game",
        position = 2,
        section = connectionSection
    )
    default String username() {
        return "";
    }

    @ConfigItem(
        keyName = "token",
        name = "Session token",
        description = "The token Voidburn messaged you after login: \"RuneLite plugin token: ...\"",
        position = 3,
        section = connectionSection,
        secret = true
    )
    default String token() {
        return "";
    }
}
