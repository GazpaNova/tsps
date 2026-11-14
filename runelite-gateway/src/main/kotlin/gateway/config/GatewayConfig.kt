package gateway.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * Settings come from the repository's root `.env`, the same file `scripts/wt` writes each
 * worktree's ports into, so the gateway always pairs with this checkout's tsps server.
 * Process environment variables win over `.env`.
 */
data class GatewayConfig(
    val root: Path,
    val gatewayPort: Int,
    val websocketPort: Int,
    val cacheDirectory: Path,
    val keyFile: Path,
    /** The host players reach tsps and the gateway at (`PUBLIC_HOST`), as tsps's jav_config gives it. */
    val publicHost: String = DEFAULT_PUBLIC_HOST,
    /**
     * The address the game listener binds (`GATEWAY_BIND_HOST`): loopback by default, `0.0.0.0` (or
     * one interface's address) for players on other machines. PUBLIC_HOST is what they're told to
     * connect to, which behind NAT or a domain needn't be a local interface.
     */
    val bindHost: String = DEFAULT_BIND_HOST,
    val loginUtilitiesPort: Int = 0,
    val loginUtilitiesTrustLoopbackProxy: Boolean = false,
    val voidburnPluginPort: Int = 0,
    /**
     * Whether to advertise HANDSHAKE_CAPABILITY_VOIDBURN_PLUGIN on every outbound
     * tsps handshake. Default false preserves the stock capability-aware fallback
     * (tsps routes native sessions to the 378 welcome screen); set true on a
     * Voidburn-dedicated gateway where all RuneLite users are expected to run
     * the Voidburn plugin so that tsps's MOTD plugin becomes eligible for every
     * session this gateway handles.
     *
     * This is a per-DEPLOYMENT setting, not per-client detection. A vanilla
     * RuneLite reaching an opted-in gateway (direct connect, RSProx) will still
     * have the capability advertised on its behalf and will receive MOTD
     * packets it cannot render. Enable this flag only when the launcher/plugin
     * distribution and late-attach replay ship together.
     *
     * Also requires `voidburnPluginPort` to be a non-zero port: the listener
     * must be running to receive the plugin WebSocket attach that forwards
     * MOTD state to the player. Enabling advertisement without the listener is
     * a configuration error; `load` fails fast in that case.
     */
    val advertiseVoidburnPluginCapability: Boolean = false,
) {
    init {
        require(!advertiseVoidburnPluginCapability || voidburnPluginPort > 0) {
            "ADVERTISE_VOIDBURN_PLUGIN_CAPABILITY requires VOIDBURN_PLUGIN_PORT to be set — " +
                "advertising the capability without a running plugin listener leaves RuneLite " +
                "sessions unable to render the MOTD the server will send them."
        }
    }

    val tspsUrl: String get() = "ws://127.0.0.1:$websocketPort/"

    companion object {
        const val DEFAULT_GATEWAY_PORT = 43595
        const val DEFAULT_WEBSOCKET_PORT = 43594
        const val DEFAULT_PUBLIC_HOST = "127.0.0.1"
        const val DEFAULT_BIND_HOST = "127.0.0.1"

        fun load(root: Path = Path.of("").toAbsolutePath()): GatewayConfig {
            val env = readEnv(root.resolve(".env")) + System.getenv()
            val target = Files.readString(root.resolve("server/target.txt")).trim()
            val keyFile = root.resolve("runelite-gateway/keys/rsa.txt")
            val legacyKeyFile = root.resolve("gateway/keys/rsa.txt")
            return GatewayConfig(
                root = root,
                gatewayPort = env["GATEWAY_PORT"]?.toInt() ?: DEFAULT_GATEWAY_PORT,
                websocketPort = env["WEBSOCKET_PORT"]?.toInt() ?: DEFAULT_WEBSOCKET_PORT,
                cacheDirectory = root.resolve("server/caches").resolve(target),
                keyFile = if (!Files.exists(keyFile) && Files.exists(legacyKeyFile)) legacyKeyFile else keyFile,
                publicHost = env["PUBLIC_HOST"]?.takeIf { it.isNotBlank() } ?: DEFAULT_PUBLIC_HOST,
                bindHost = env["GATEWAY_BIND_HOST"]?.takeIf { it.isNotBlank() } ?: DEFAULT_BIND_HOST,
                loginUtilitiesPort = (env["GATEWAY_LOGIN_HTTP_PORT"] ?: env["LOGIN_UTILITIES_PORT"])?.toInt() ?: 0,
                loginUtilitiesTrustLoopbackProxy = (env["GATEWAY_LOGIN_TRUST_LOOPBACK_PROXY"] ?: env["LOGIN_UTILITIES_TRUST_LOOPBACK_PROXY"]) == "true",
                voidburnPluginPort = env["VOIDBURN_PLUGIN_PORT"]?.toInt() ?: 0,
                advertiseVoidburnPluginCapability =
                    env["ADVERTISE_VOIDBURN_PLUGIN_CAPABILITY"]?.toBoolean() ?: false,
            )
        }

        private fun readEnv(file: Path): Map<String, String> {
            if (!Files.exists(file)) return emptyMap()
            return Files.readAllLines(file)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().trim('"') }
        }
    }
}
