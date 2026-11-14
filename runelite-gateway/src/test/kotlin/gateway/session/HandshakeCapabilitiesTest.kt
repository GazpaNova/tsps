package gateway.session

import gateway.config.GatewayConfig
import gateway.tsps.TspsClientPacket
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins GatewaySession's handshake capability computation against
 * GatewayConfig's advertise flag. Both handshake call sites call
 * `computeHandshakeCapabilities(context.config)` so a mismatch here
 * is the mismatch on the wire.
 */
class HandshakeCapabilitiesTest {
    @Test
    fun `default config (advertise off) returns no extra capabilities`() {
        val config = minimalConfig()
        assertEquals(0, computeHandshakeCapabilities(config),
            "default-off deployment must leave tsps's capability-aware fallback intact")
    }

    @Test
    fun `advertise on returns the voidburn plugin capability bit`() {
        val config = minimalConfig(
            voidburnPluginPort = 43701,
            advertiseVoidburnPluginCapability = true,
        )
        assertEquals(
            TspsClientPacket.CAPABILITY_VOIDBURN_PLUGIN,
            computeHandshakeCapabilities(config),
            "opt-in deployment must set the voidburn plugin bit on every handshake",
        )
    }

    @Test
    fun `config fails fast when advertise is on but plugin listener is disabled`() {
        // Advertising without a listener running would leave RuneLite
        // sessions unable to render the MOTD the server will send them.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            minimalConfig(
                voidburnPluginPort = 0,
                advertiseVoidburnPluginCapability = true,
            )
        }
        assertTrue(
            failure.message?.contains("VOIDBURN_PLUGIN_PORT") == true,
            "error message must name the port setting the operator needs to set",
        )
    }

    @Test
    fun `config accepts advertise on when plugin listener port is set`() {
        // Positive control: the same shape as the failing case but with a
        // non-zero port now constructs cleanly.
        val config = minimalConfig(
            voidburnPluginPort = 43701,
            advertiseVoidburnPluginCapability = true,
        )
        assertTrue(config.advertiseVoidburnPluginCapability)
        assertEquals(43701, config.voidburnPluginPort)
    }

    private fun minimalConfig(
        voidburnPluginPort: Int = 0,
        advertiseVoidburnPluginCapability: Boolean = false,
    ): GatewayConfig = GatewayConfig(
        root = Path.of("/tmp/test"),
        gatewayPort = 43595,
        websocketPort = 43594,
        cacheDirectory = Path.of("/tmp/cache"),
        keyFile = Path.of("/tmp/rsa.txt"),
        voidburnPluginPort = voidburnPluginPort,
        advertiseVoidburnPluginCapability = advertiseVoidburnPluginCapability,
    )
}
