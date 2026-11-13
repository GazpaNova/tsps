package com.voidburn.plugin;

import com.google.inject.Provides;
import java.util.Optional;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RuneLite plugin entry point.
 *
 * Owns a single PluginConnection at a time. The connection is started when
 * the plugin starts and the config is complete; it is stopped and re-created
 * when the config changes or the plugin is disabled. We deliberately do not
 * try to live-reconfigure an existing socket — tearing it down and
 * reconnecting makes the state machine trivial and matches the gateway's
 * expectation (a reconnect re-attaches cleanly).
 *
 * Phase 2c-2a: this plugin logs received frames and nothing else. Phase 2c-2b
 * wires in the decoder and interface state; Phase 2c-3 adds the overlay.
 */
@PluginDescriptor(
    name = "Voidburn",
    description = "Renders Voidburn custom interfaces (MOTD, custom shops, teleport menus)",
    tags = {"voidburn", "rsps", "motd"},
    enabledByDefault = false
)
public class VoidburnPlugin extends Plugin {
    private static final Logger log = LoggerFactory.getLogger(VoidburnPlugin.class);

    @Inject
    private VoidburnConfig config;

    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    /**
     * Live view of what custom interfaces are open on this session. Written from the
     * OkHttp dispatcher via onFrame; readable from any thread (the InterfaceState's
     * records are immutable and its backing map is a ConcurrentHashMap). Phase 2c-3c
     * consumes this on the client thread from MotdOverlay.
     */
    private final InterfaceState interfaceState = new InterfaceState();

    private MotdOverlay motdOverlay;
    private PluginConnection connection;

    @Provides
    VoidburnConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(VoidburnConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("Voidburn plugin starting");
        motdOverlay = new MotdOverlay(client, () -> Optional.of(interfaceState));
        overlayManager.add(motdOverlay);
        openConnection();
    }

    @Override
    protected void shutDown() {
        log.info("Voidburn plugin stopping");
        closeConnection();
        if (motdOverlay != null) {
            overlayManager.remove(motdOverlay);
            motdOverlay = null;
        }
    }

    /**
     * RuneLite dispatches this when the player picks one of the overlay's
     * right-click menu entries. "Close MOTD" dismisses locally; Phase 2d will
     * route a real server-side click through the gateway instead.
     */
    @Subscribe
    public void onOverlayMenuClicked(OverlayMenuClicked event) {
        if (motdOverlay == null) return;
        if (event.getOverlay() != motdOverlay) return;
        if (event.getEntry().getMenuAction() != MenuAction.RUNELITE_OVERLAY) return;
        if ("Close MOTD".equals(event.getEntry().getOption())) {
            motdOverlay.dismiss();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!VoidburnConfig.GROUP.equals(event.getGroup())) return;
        // Any config edit (URL, username, token) warrants a reconnect. The
        // gateway side can't be live-rebound either — the new token is a
        // new session's mint, and a new username is a different attach.
        log.info("Voidburn plugin: config changed ({}) — reconnecting", event.getKey());
        closeConnection();
        openConnection();
    }

    private void openConnection() {
        String url = safe(config.gatewayUrl());
        String username = safe(config.username());
        String token = safe(config.token());
        if (url.isEmpty() || username.isEmpty() || token.isEmpty()) {
            log.info("Voidburn plugin: config incomplete (gatewayUrl/username/token required) — not connecting");
            return;
        }
        // A fresh session should show MOTD again — clear any local dismissal
        // from the retired connection. The InterfaceState reset already ran in
        // closeConnection() so the overlay won't show the OLD session's MOTD
        // between here and the first frame; it just re-arms the show logic for
        // when the new session's widget_open lands.
        if (motdOverlay != null) {
            motdOverlay.reset();
        }
        connection = new PluginConnection(url, username, token, this::onFrame);
        connection.start();
    }

    private void closeConnection() {
        if (connection != null) {
            // Stop the connection first so no further frames can reach interfaceState.
            // PluginConnection.stop() tears down under its lifecycle monitor; any late
            // OkHttp callback sees stopped=true and short-circuits before apply() is
            // reached. Only then clear state, so a replacement session (new login,
            // new config, or plugin re-enable) starts from an empty view and cannot
            // inherit the retired session's root or mounts.
            connection.stop();
            connection = null;
            interfaceState.reset();
        }
    }

    private void onFrame(String frame) {
        // Phase 2c-2b: decode the frame and apply to the interface state. The
        // decoder never throws; malformed or unknown frames land as Frame.Unknown
        // and apply() treats them as no-ops. Phase 2c-3's overlay reads from
        // interfaceState on the client thread; this update is on OkHttp's thread.
        Frame decoded = FrameDecoder.decode(frame);
        if (decoded instanceof Frame.Unknown unknown) {
            log.debug("Voidburn plugin: unknown frame type {}: {}", unknown.type(), unknown.rawJson());
            return;
        }
        interfaceState.apply(decoded);
    }

    /** Package-private accessor for tests and the future overlay. */
    InterfaceState interfaceState() {
        return interfaceState;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
