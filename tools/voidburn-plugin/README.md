# Voidburn RuneLite plugin

Lets RuneLite users on Voidburn see server-sent custom interfaces (MOTD, custom
shops, teleport menus — anything whose group id is `>= 30000`) that the official
OSRS client has no cached definition for.

Build artifact: a RuneLite plugin JAR the player drops into their
`~/.runelite/externalmanager/plugins/` directory (or sideloads via `-classpath`).

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
`net.runelite:client` dependency.

```bash
cd tools/voidburn-plugin
./gradlew test build
```

The compiled JAR is `build/libs/voidburn-plugin-*.jar`.

## Install (dev workflow)

1. Build a JAR as above.
2. Copy it into `~/.runelite/externalmanager/plugins/` (create the directory if
   needed). Or sideload with `-classpath` when running RuneLite's launcher.
3. Start RuneLite. The plugin appears in the plugins list as "Voidburn".
4. Open its config:
   - **Gateway URL** — e.g. `ws://127.0.0.1:8081/plugin` for a local gateway.
   - **OSRS username** — your in-game display name, case-sensitive.
   - **Session token** — paste the token the server messaged you at login.
5. The plugin connects and attaches. See the RuneLite log for frames received.

The token rotates every time you log in; paste in the new one if you relog.

## Not yet in this module

- Frame decoder and interface state (Phase 2c-2b).
- Overlay rendering (Phase 2c-3).
- Click handling (Phase 2d).
- Signed distribution via the RuneLite Plugin Hub (later).

## License

The plugin is part of Voidburn (same license as the rest of this repo). The
RuneLite client dependency keeps its own license (BSD-2, net.runelite).
