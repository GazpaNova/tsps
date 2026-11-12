# Voidburn RuneLite plugin

Lets RuneLite users on Voidburn see server-sent custom interfaces (MOTD, custom
shops, teleport menus — anything whose group id is `>= 30000`) that the official
OSRS client has no cached definition for.

Build artifact: a RuneLite plugin JAR. For now it's loaded through a small
developer launcher (`VoidburnPluginLauncher`) that registers the plugin with
`ExternalPluginManager.loadBuiltin` before launching RuneLite. User-facing
distribution via the RuneLite Plugin Hub is a future phase.

```
RuneLite ─ plugin ──── WebSocket /plugin ────▶ Voidburn gateway
                       (attach + token)          (voidburn-plugin-port)
```

The plugin authenticates with a per-session token the player gets from the game
server right after login — a chat message that reads
`RuneLite plugin token: <token>` — and pastes into the plugin's config. The
gateway's side of this handshake is in `runelite-gateway/src/main/kotlin/
gateway/plugin/`; see its Phase 2b/2c-1 handoffs for the protocol, the
authorization model and the limits.

Phase 2c-2a (this module's current state): the plugin connects, authenticates,
and logs every frame the gateway forwards. It does NOT yet decode frames into
typed shapes or draw anything on the RuneLite canvas — that is Phase 2c-2b
(decoder + interface state) and 2c-3 (rendering).

## Build

You need Java 21 and network access to `repo.runelite.net` for the
`net.runelite:client` dependency. This module has no wrapper of its own;
the repository's existing Gradle wrapper under `runelite-gateway/` drives it:

```bash
bash runelite-gateway/gradlew -p tools/voidburn-plugin test build
```

The compiled JAR is `build/libs/voidburn-plugin-0.1.0.jar`.

## Run (dev workflow)

Stock RuneLite 1.13.3 loads external plugins via
`ExternalPluginManager.loadBuiltin(…)` before `RuneLite.main(…)` — not from
a drop-in directory, and not from an ordinary classpath entry. This module
ships a small launcher at `src/test/java/com/voidburn/plugin/VoidburnPluginLauncher.java`
that follows the stock pattern.

To run RuneLite with the plugin loaded during development:

- From an IDE: Run `com.voidburn.plugin.VoidburnPluginLauncher` as a Java
  application. Program arguments are forwarded to `RuneLite.main(args)`;
  pass `--developer-mode` to enable developer tooling.
- From the command line: launch the test classpath directly. The exact
  invocation depends on how you resolve the RuneLite jar (Gradle, your
  IDE's classpath, or an exported script); see RuneLite's own
  [example-plugin](https://github.com/runelite/example-plugin) for the
  canonical shape.

Once RuneLite is up:

1. Open the settings panel for the "Voidburn" plugin.
2. Fill in:
   - **Gateway URL** — e.g. `ws://127.0.0.1:8081/plugin` for a local gateway.
   - **OSRS username** — your in-game display name, case-sensitive.
   - **Session token** — paste the token Voidburn messaged you after login.
3. The plugin connects, authenticates, and logs received frames at DEBUG.

The token rotates every time you log in; paste in the new one if you relog.

## Not yet in this module

- Frame decoder and interface state (Phase 2c-2b).
- Overlay rendering (Phase 2c-3).
- Click handling (Phase 2d).
- Signed distribution via the RuneLite Plugin Hub (later).

## License

The plugin is part of Voidburn (same license as the rest of this repo). The
RuneLite client dependency keeps its own license (BSD-2, net.runelite).
