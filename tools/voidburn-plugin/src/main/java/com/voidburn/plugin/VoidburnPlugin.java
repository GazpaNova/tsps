package com.voidburn.plugin;

import com.google.inject.Provides;
import java.awt.event.MouseEvent;
import java.util.Optional;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
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

    @Inject
    private MouseManager mouseManager;

    @Inject
    private ConfigManager configManager;

    /**
     * Live view of what custom interfaces are open on this session. Written from the
     * OkHttp dispatcher via onFrame; readable from any thread (the InterfaceState's
     * records are immutable and its backing map is a ConcurrentHashMap). Phase 2c-3c
     * consumes this on the client thread from MotdOverlay.
     */
    private final InterfaceState interfaceState = new InterfaceState();

    private MotdOverlay motdOverlay;
    private PluginConnection connection;
    private MouseAdapter mouseListener;
    private final UrlOpener urlOpener = new UrlOpener();

    @Provides
    VoidburnConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(VoidburnConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("Voidburn plugin starting");
        motdOverlay = new MotdOverlay(client, () -> Optional.of(interfaceState));
        overlayManager.add(motdOverlay);
        mouseListener = new MotdMouseListener();
        mouseManager.registerMouseListener(mouseListener);
        openConnection();
    }

    @Override
    protected void shutDown() {
        log.info("Voidburn plugin stopping");
        closeConnection();
        if (mouseListener != null) {
            mouseManager.unregisterMouseListener(mouseListener);
            mouseListener = null;
        }
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
        // Any connection-shaping edit (URL, username, token) warrants a
        // reconnect. The gateway side can't be live-rebound either — the
        // new token is a new session's mint, and a new username is a
        // different attach. The autoPair toggle itself only changes
        // whether future auto-captures write to config; no reconnect is
        // needed for that edit.
        if (VoidburnConfig.KEY_AUTO_PAIR.equals(event.getKey())) {
            log.debug("Voidburn plugin: autoPair toggled to {}", event.getNewValue());
            return;
        }
        log.info("Voidburn plugin: config changed ({}) — reconnecting", event.getKey());
        closeConnection();
        openConnection();
    }

    /**
     * Auto-pair, step 1: on game login, fill the username from the client
     * if the player hasn't set it. The player's display name only
     * becomes available on LOGGED_IN, not on earlier states —
     * Client.getLocalPlayer() returns null before login.
     *
     * Liveness: if getLocalPlayer() is not yet populated when this event
     * fires (RuneLite's deferred-init ordering can race the first
     * LOGGED_IN with Client.getLocalPlayer()'s own first non-null
     * publication), the capture is NOT one-shot. tryFillUsername() is
     * also called from onChatMessage, so when the token arrives (which
     * the gateway always sends after login completes, by which time the
     * local player is populated), the plugin has another shot at
     * filling the username. v1 review P2 regression.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() != GameState.LOGGED_IN) return;
        tryFillUsername();
    }

    /**
     * Try to write the username config key from the logged-in player's
     * name. Idempotent: no-op when autoPair is off, when the client has
     * no local player yet, when the name is invalid, or when the config
     * already holds that name. Safe to call from both the LOGGED_IN
     * handler and the chat-token handler — the next call picks up where
     * the previous no-op left off.
     */
    private void tryFillUsername() {
        Player local = client.getLocalPlayer();
        if (local == null) return;
        Optional<String> name = AutoPair.usernameToWrite(
            config.autoPair(), local.getName(), config.username());
        if (name.isEmpty()) return;
        log.info("Voidburn plugin: auto-pair filling username from logged-in player");
        configManager.setConfiguration(VoidburnConfig.GROUP, VoidburnConfig.KEY_USERNAME, name.get());
    }

    /**
     * Auto-pair, step 2: capture the session token from the gateway's
     * post-login chat message ("RuneLite plugin token: …"). Only acts
     * on GAMEMESSAGE events (the type the gateway uses); other chat
     * sources don't match, so a player typing the prefix by hand into
     * a public channel can't poison the config.
     *
     * Also catches a deferred-username case: the gateway always sends
     * the token line after login has fully completed, so by the time
     * we see it, Client.getLocalPlayer() is reliably populated. A
     * tryFillUsername() call here recovers from a null-getLocalPlayer
     * race at LOGGED_IN. The two writes are independent
     * ConfigManager.setConfiguration calls and the
     * {@link #onConfigChanged} subscriber reconnects for each; the
     * gateway's attach reads both values from a single config read, so
     * ordering them within the same tick is fine.
     *
     * The write triggers the usual reconnect via onConfigChanged, and
     * the attach then authenticates against the fresh mint the gateway
     * holds for this session.
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE) return;
        Optional<String> token = AutoPair.tokenToWrite(
            config.autoPair(), event.getMessage(), config.token());
        if (token.isEmpty()) return;
        // Recover a username that the LOGGED_IN handler couldn't capture
        // because getLocalPlayer was still null at that event (v1 review
        // P2). The token always arrives post-login, so by now the Client
        // has a local player.
        tryFillUsername();
        log.info("Voidburn plugin: auto-pair captured session token from chat");
        configManager.setConfiguration(VoidburnConfig.GROUP, VoidburnConfig.KEY_TOKEN, token.get());
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

    /**
     * Routes left-clicks that land on a MOTD hit region to the server as
     * plugin → gateway click frames. Only {@link MouseEvent#BUTTON1 left
     * clicks} are consumed here; every other mouse event is passed through
     * untouched so RuneLite's normal game handling (camera, right-click
     * menus, middle-click) runs unaffected.
     *
     * Runs on the AWT event thread. Reads the overlay's published hit
     * regions via {@link MotdOverlay#hitTest}, which is volatile-safe
     * against the client-thread render.
     *
     * Consumption contract: when a click lands on a hit region, the
     * MouseEvent is {@link MouseEvent#consume consumed} before being
     * returned, so the canvas's own click handler does not also see the
     * click (an un-consumed click on top of the panel would also fire a
     * "walk here" or a NPC click underneath). When the click misses every
     * region, the event is returned unchanged.
     */
    private final class MotdMouseListener extends MouseAdapter {
        @Override
        public MouseEvent mouseClicked(MouseEvent event) {
            return maybeHandle(event);
        }

        @Override
        public MouseEvent mousePressed(MouseEvent event) {
            // Consuming mousePressed is what keeps the canvas from starting
            // a left-click-drag (camera rotate etc) when the player presses
            // on the panel. We don't act on it — the click is fired on
            // release from mouseClicked — but we DO consume the press that
            // lands on our hit region so the game doesn't treat it as the
            // start of a world interaction.
            return maybeHandle(event);
        }

        private MouseEvent maybeHandle(MouseEvent event) {
            if (motdOverlay == null) return event;
            if (event.getButton() != MouseEvent.BUTTON1) return event;
            Optional<MotdOverlay.HitRegion> hit = motdOverlay.hitTest(event.getX(), event.getY());
            if (hit.isEmpty()) return event;
            // mousePressed arrives before mouseClicked; we only want to
            // dispatch once per click, on mouseClicked. mousePressed still
            // consumes so the canvas doesn't start a drag on our panel.
            if (event.getID() == MouseEvent.MOUSE_CLICKED) {
                dispatchClick(hit.get());
            }
            event.consume();
            return event;
        }

        private void dispatchClick(MotdOverlay.HitRegion region) {
            // Switch on the action variant — Play goes upstream to the
            // gateway, URL opens locally in the player's browser. Only
            // the upstream path dismisses the overlay (the server's
            // widget_set_root follows). A URL click leaves the overlay
            // alone so the player can click a different URL without
            // retriggering the welcome screen.
            MotdOverlay.HitAction action = region.action();
            if (action instanceof MotdOverlay.HitAction.Click click) {
                dispatchServerClick(click);
            } else if (action instanceof MotdOverlay.HitAction.OpenUrl openUrl) {
                dispatchUrlOpen(openUrl);
            }
        }

        private void dispatchServerClick(MotdOverlay.HitAction.Click click) {
            PluginConnection c = connection;
            if (c == null) {
                log.debug("Voidburn plugin: click on widget {} dropped — no connection", click.widgetUid());
                return;
            }
            String frame = ClickFrame.build(click.widgetUid(), click.op());
            boolean sent = c.send(frame);
            if (!sent) {
                log.debug("Voidburn plugin: click on widget {} dropped — connection not ready", click.widgetUid());
                return;
            }
            // The server's widget_set_root follows on the back of this
            // click; dismissing locally gives the user instant visual
            // feedback instead of waiting a tick for the root change to
            // land. Dismissal is cleared on the next openConnection()
            // (new login / config change), so re-opening the plugin after
            // a Play click still shows MOTD if the server reopens it.
            motdOverlay.dismiss();
        }

        private void dispatchUrlOpen(MotdOverlay.HitAction.OpenUrl openUrl) {
            boolean opened = urlOpener.open(openUrl.url());
            if (!opened) {
                log.info("Voidburn plugin: could not open URL {}", openUrl.url());
            }
            // URL click does NOT dismiss the overlay — same behaviour as
            // the TypeScript client's CustomInterfaceRuntime: open the
            // URL and leave the welcome screen up so the player can
            // click another URL or the Play button.
        }
    }
}
