package gateway

import gateway.cache.Cache
import gateway.cache.ClientCache
import gateway.config.GatewayConfig
import gateway.crypto.RsaKeys
import gateway.net.CacheJs5Provider
import gateway.net.GatewayNetwork
import gateway.session.GatewayConnections
import gateway.session.GatewayContext
import gateway.up.UpdatePlayerModel
import io.netty.buffer.Unpooled
import net.rsprot.compression.HuffmanCodec
import net.rsprot.compression.provider.DefaultHuffmanCodecProvider
import net.rsprot.crypto.rsa.RsaKeyPair
import net.rsprot.protocol.api.NetworkService

/** A running gateway: the rsprot network service and what it was built from. */
class Gateway private constructor(
    val config: GatewayConfig,
    val rsa: RsaKeyPair,
    val revision: Int,
    private val network: NetworkService<*>,
    private val context: GatewayContext,
) {
    fun stop() {
        context.voidburnPluginServer.stop()
        context.loginUtilities?.close()
        network.shutdownNow()
    }

    companion object {
        private const val BINARY_ARCHIVE = 10

        @OptIn(ExperimentalStdlibApi::class)
        fun start(config: GatewayConfig): Gateway {
            val rsa = RsaKeys.loadOrCreate(config.keyFile)
            val cache = Cache.open(config.cacheDirectory)
            val huffmanTable = requireNotNull(cache.file(BINARY_ARCHIVE, "huffman")) { "no huffman table in the cache" }
            val huffman = DefaultHuffmanCodecProvider(HuffmanCodec.create(Unpooled.wrappedBuffer(huffmanTable)))
            // Cache directories are named osrs-<revision>_<date>.
            val revision = config.cacheDirectory.fileName.toString().substringAfter("osrs-").substringBefore('_').toInt()
            val context = GatewayContext(config, cache, huffman, revision)
            val network = GatewayNetwork(config.bindHost, config.gatewayPort, rsa, huffman, CacheJs5Provider(ClientCache(cache)), GatewayConnections(context)).build()
            UpdatePlayerModel.install(network)
            network.start()
            // The plugin listener is optional: if its port is in use or otherwise fails
            // to bind, log it and continue — the game listener is already up and nothing
            // else depends on the plugin side channel. Start it AFTER rsprot so this
            // isolation never affects game startup, and so nothing need be unwound here.
            try {
                context.voidburnPluginServer.start()
            } catch (t: Throwable) {
                java.util.logging.Logger.getLogger("gateway.Gateway")
                    .warning("Voidburn plugin listener failed to start: ${t.message}; continuing without it")
            }
            return Gateway(config, rsa, revision, network, context)
        }
    }
}
