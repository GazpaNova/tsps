package gateway.plugin

import gateway.session.VoidburnPluginChannel
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Netty-based WebSocket server that accepts Voidburn RuneLite plugin connections,
 * attaches them to the matching GatewaySession via the registry, and tears the
 * attachment down when the connection closes.
 *
 * Lifecycle: start() binds the listener; stop() closes it synchronously. Each accepted
 * connection runs a brief handshake — the plugin sends `{"type":"attach","username":...}`
 * as the first TEXT frame — and then the connection becomes the session's
 * VoidburnPluginChannel until it closes.
 *
 * Bind: loopback-only unless the config explicitly asks for another address. Phase 2b
 * does not authenticate attach messages beyond the registry lookup; see the registry
 * docstring for the Phase 2c follow-up gate.
 *
 * Not reused for other Netty servers in the gateway. rsprot's own ServerBootstrap
 * handles the game TCP listener; this is a small separate stack for the plugin side
 * channel.
 */
class VoidburnPluginServer(
    private val port: Int,
    private val bindHost: String,
    private val registry: VoidburnPluginRegistry,
) {
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var listenerChannel: Channel? = null

    /**
     * Start the listener. No-op when port is 0 (disabled by default). Blocking until the
     * bind completes; a failed bind throws.
     */
    fun start() {
        if (port == 0) return
        val boss = NioEventLoopGroup(1)
        val worker = NioEventLoopGroup()
        bossGroup = boss
        workerGroup = worker
        val bootstrap = ServerBootstrap()
            .group(boss, worker)
            .channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(HttpServerCodec())
                        .addLast(HttpObjectAggregator(MAX_HTTP_CONTENT_BYTES))
                        .addLast(WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, MAX_FRAME_BYTES.toInt()))
                        .addLast(PluginConnectionHandler(registry))
                }
            })
        listenerChannel = bootstrap.bind(InetSocketAddress(bindHost, port)).sync().channel()
    }

    /**
     * Shut the listener and every active connection down. Idempotent.
     */
    fun stop() {
        listenerChannel?.close()?.awaitUninterruptibly()
        listenerChannel = null
        workerGroup?.shutdownGracefully()?.awaitUninterruptibly()
        bossGroup?.shutdownGracefully()?.awaitUninterruptibly()
        workerGroup = null
        bossGroup = null
    }

    private companion object {
        const val WEBSOCKET_PATH = "/plugin"
        /** 16 KiB is enough for the handshake HTTP request plus headroom. */
        const val MAX_HTTP_CONTENT_BYTES = 16 * 1024
        /** 64 KiB per frame; Phase 2c will serialize whole interface definitions over this. */
        const val MAX_FRAME_BYTES = 64 * 1024L
    }
}

/**
 * Per-connection state. Accepts exactly one attach frame to resolve the session, then
 * acts as that session's VoidburnPluginChannel until closed.
 */
private class PluginConnectionHandler(
    private val registry: VoidburnPluginRegistry,
) : SimpleChannelInboundHandler<TextWebSocketFrame>(), VoidburnPluginChannel {
    private var attachedUsername: String? = null
    private var ctx: ChannelHandlerContext? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        this.ctx = ctx
    }

    override fun channelRead0(ctx: ChannelHandlerContext, frame: TextWebSocketFrame) {
        val payload = frame.text()
        if (attachedUsername == null) {
            val username = VoidburnPluginMessages.parseAttach(payload)
            if (username == null) {
                log.fine("rejecting malformed attach from ${ctx.channel().remoteAddress()}")
                ctx.close()
                return
            }
            if (!registry.attach(username, this)) {
                log.fine("no session for '$username' from ${ctx.channel().remoteAddress()}")
                ctx.close()
                return
            }
            attachedUsername = username
            log.fine("plugin attached to session '$username'")
            return
        }
        // Phase 2c and beyond: additional message types (click events, keepalive) go
        // here. Phase 2b only expects the attach frame, so anything else is logged
        // and ignored without tearing the connection down (keeps compatibility if the
        // plugin starts sending status messages before the gateway grows handlers).
        log.finer("ignoring plugin message type pre-Phase-2c: '${payload.take(80)}'")
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val username = attachedUsername
        if (username != null) {
            registry.detach(username)
            attachedUsername = null
        }
        this.ctx = null
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.log(Level.FINE, "plugin connection error", cause)
        ctx.close()
    }

    override fun send(message: String) {
        val channel = ctx?.channel() ?: return
        if (!channel.isActive) return
        // writeAndFlush is non-blocking; failure to write just logs and drops the
        // message per the VoidburnPluginChannel contract (best-effort, no retry).
        channel.writeAndFlush(TextWebSocketFrame(Unpooled.copiedBuffer(message, StandardCharsets.UTF_8)))
            .addListener(ChannelFutureListener { future: ChannelFuture ->
                if (!future.isSuccess) {
                    log.log(Level.FINE, "plugin send failed", future.cause())
                }
            })
    }

    private companion object {
        val log: Logger = Logger.getLogger("gateway.plugin.VoidburnPluginServer")
    }
}
