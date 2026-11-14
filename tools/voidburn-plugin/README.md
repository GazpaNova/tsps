# Voidburn RuneLite plugin

Lets RuneLite players on Voidburn see server-sent custom interfaces (MOTD,
custom shops, teleport menus — anything with group id `>= 30000`) that the
official OSRS client has no cached definition for.

```
RuneLite ── plugin ──── WebSocket /plugin ────▶ Voidburn gateway
                        (attach + token)         (VOIDBURN_PLUGIN_PORT)
```

## Current capabilities

- **Attach + authenticate** with a per-session token the gateway sends as a
  game chat message after login (`"RuneLite plugin token: <token>"`).
- **Auto-pair** at login: the plugin reads the token chat line and fills the
  config itself, and picks up the OSRS username from the logged-in player.
  No manual Settings pass once the plugin is installed and the launcher's
  default gateway URL is correct. Fall back to manual mode by turning off
  "Auto-pair on login".
- **MOTD overlay**: renders the server-sent welcome panel (title, tagline,
  about/rates/changes/what's-new sections, three footer URL lines, a Play
  button), tracked by `widget_set_text` and `widget_set_hidden` updates.
- **Play button click** → routed through the gateway as an `if_button_x`
  packet to tsps. Overlay dismisses locally on click for instant feedback;
  the server's root rotation retires the MOTD state authoritatively.
- **URL footer clicks** open in the player's default browser via
  `java.awt.Desktop.browse` without a server round-trip. Strict
  `https?://`-scheme filter plus a non-empty-host check at the boundary.
- **Shift + right-click → "Close MOTD"** for a local dismiss without
  clicking Play (when the overlay is partially covering something the player
  wants to see first).

## Server + gateway prerequisites

The plugin is one half of the Voidburn MOTD path. For a RuneLite player to
actually see the MOTD panel, the operator also needs:

- **`WelcomeScreen:deferToMotd = true`** in the world's plugin config, so
  the server runs the MOTD plugin (otherwise the stock 378 welcome shows).
- **Gateway `ADVERTISE_VOIDBURN_PLUGIN_CAPABILITY=true`** and
  **`VOIDBURN_PLUGIN_PORT=<port>`** on the Voidburn gateway. Without the
  capability bit at handshake, tsps routes native sessions to the stock
  welcome; without the listener port, the plugin can't attach. The config's
  `init` block rejects advertise=true + port=0.
- **Late-attach replay buffer** on the gateway (already in place), so
  MOTD packets the server emits during the pre-attach window are buffered
  in order and delivered when the plugin attaches.

See `runelite-gateway/README` for the gateway side.

## Build

Java 21 and network access to `repo.runelite.net` for the
`net.runelite:client` dependency. The repository's existing Gradle wrapper
under `runelite-gateway/` drives this module too:

```bash
bash runelite-gateway/gradlew -p tools/voidburn-plugin test build
```

Artifact: `tools/voidburn-plugin/build/libs/voidburn-plugin-<version>.jar`.

The JAR contains only the plugin's own classes + resources. Every
transitive dependency the plugin uses — Guice (plugin DI), OkHttp
(WebSocket), Gson (JSON), SLF4J (logging) — comes from the RuneLite
client at runtime, so no shadow/shade step is needed. The manifest
carries `Implementation-Title`, `Implementation-Version`,
`Implementation-Vendor`, and `Plugin-Class` (informational, for tooling
that inspects the JAR without parsing the class tree).

Jar entries are produced with `preserveFileTimestamps = false` and
`reproducibleFileOrder = true`, so a clean rebuild from the same source
commit produces a byte-identical JAR. A launcher's integrity check (SHA
of the bundled JAR) stays stable between builds.

## Bundling into a launcher

Stock RuneLite 1.13.3 loads external plugins via
`ExternalPluginManager.loadBuiltin(…)` before `RuneLite.main(…)` — not
from a drop-in directory, not from an ordinary classpath entry. A launcher
that ships with the Voidburn plugin preinstalled follows the same pattern
`VoidburnPluginLauncher` uses in the dev workflow:

1. Add `voidburn-plugin-<version>.jar` and the RuneLite client JAR to the
   launcher's classpath (both are produced through the normal RuneLite
   external-plugin build chain).
2. Launcher's `main` calls, in order:

   ```java
   ExternalPluginManager.loadBuiltin(com.voidburn.plugin.VoidburnPlugin.class);
   RuneLite.main(args);
   ```

   The `Plugin-Class` manifest attribute names the class if you're
   generating the loader list dynamically from a bundled plugins
   directory.

3. On first launch, write the production gateway URL to RuneLite's
   config for the Voidburn plugin's `gatewayUrl` key (config group
   `voidburn`). The plugin's default is a dev loopback URL
   (`ws://127.0.0.1:8081/plugin`) that a vanilla RuneLite user would edit
   themselves — a launcher-bundled player should never see or touch that
   setting.

4. That's it on the plugin side. The server/gateway prereqs above
   complete the path.

### What the launcher does NOT need to supply

- **Token** — the plugin auto-captures it from the post-login chat
  message. The launcher doesn't see or handle tokens.
- **Username** — the plugin auto-fills from
  `Client.getLocalPlayer().getName()` at `GameStateChanged(LOGGED_IN)`.
- **Per-player config** — the plugin's defaults + auto-pair are enough
  for the common case. The launcher only writes the single
  `voidburn.gatewayUrl` key on first install.

## Run (dev workflow)

Runs through `src/test/java/com/voidburn/plugin/VoidburnPluginLauncher`,
excluded from the production JAR. From an IDE: Run that main class as a
Java application; program arguments forward to `RuneLite.main(args)`.
Pass `--developer-mode` to enable developer tooling.

Once RuneLite is up, open the settings panel for the "Voidburn" plugin and
fill in the Gateway URL (e.g. `ws://127.0.0.1:8081/plugin` for a local
gateway). Everything else auto-fills on next login as long as "Auto-pair
on login" is on (default).

The token rotates every login; auto-pair reads the new one from each
login's chat message.

## Scope limits

- No Plugin Hub submission yet. Distribution is via the Voidburn launcher
  bundle (see above).
- Stock-root transitions (server rotating MOTD → 161 after Play) are not
  forwarded to the plugin; the overlay dismisses locally on Play click to
  cover the common case.
- URL extraction is a regex over the server-authored label text. A future
  gateway/server addition that pushes a `urls` map to the plugin
  explicitly is tracked but out of scope here.

## License

Part of Voidburn (same license as the rest of this repo). The RuneLite
client dependency keeps its own license (BSD-2, net.runelite).
