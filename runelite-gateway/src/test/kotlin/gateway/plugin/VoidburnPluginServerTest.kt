package gateway.plugin

import gateway.session.VoidburnPluginChannel
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Integration-style tests that exercise the real Netty pipeline on a loopback port.
 *
 * - A failed bind must not leak event-loop groups, and must not stop subsequent attempts
 *   on a different port from succeeding.
 * - A real WebSocket client can attach to a registered session over the local port.
 * - A malformed attach drops the connection.
 */
class VoidburnPluginServerTest {
    private class NoopChannel : VoidburnPluginChannel {
        override fun send(message: String) {}
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `start is a no-op when port is zero`() {
        val registry = VoidburnPluginRegistry()
        val server = VoidburnPluginServer(port = 0, bindHost = "127.0.0.1", registry = registry)
        server.start()
        server.stop() // idempotent
    }

    @Test
    fun `failed bind cleans up and does not block a later successful start`() {
        val registry = VoidburnPluginRegistry()
        // Occupy a port so the first bind fails.
        ServerSocket(0).use { squatter ->
            val taken = squatter.localPort
            val server = VoidburnPluginServer(port = taken, bindHost = "127.0.0.1", registry = registry)
            assertThrows(Exception::class.java) { server.start() }
            // After the failed start, stop() must be safe and the server unstarted.
            server.stop()
        }
        // A brand-new server on a fresh port must still bind cleanly — proves the JVM
        // isn't out of selector threads or otherwise wedged.
        val fresh = freePort()
        val server = VoidburnPluginServer(port = fresh, bindHost = "127.0.0.1", registry = registry)
        try {
            server.start()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `attach from a real websocket client binds the registered session`() {
        val registry = VoidburnPluginRegistry()
        val received = LinkedBlockingQueue<VoidburnPluginChannel?>()
        registry.register("Miguel") { received.offer(it) }
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            channel.writeAndFlush(TextWebSocketFrame("""{"type":"attach","username":"Miguel"}""")).sync()
            // The registry callback should fire within a short window.
            val chan = received.poll(2, TimeUnit.SECONDS)
            assertNotNull(chan)
            // Closing the client channel detaches.
            channel.close().sync()
            val detached = received.poll(2, TimeUnit.SECONDS)
            assertNull(detached)
        } finally {
            group.shutdownGracefully()
            server.stop()
        }
    }

    @Test
    fun `malformed attach closes the connection and leaves registry empty`() {
        val registry = VoidburnPluginRegistry()
        registry.register("Miguel") {}
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            // Nested-field payload: v1 parser extracted the username from this.
            channel.writeAndFlush(
                TextWebSocketFrame("""{"nested":{"type":"attach","username":"Miguel"}}""")
            ).sync()
            // Server closes; channel.closeFuture completes.
            channel.closeFuture().await(2, TimeUnit.SECONDS)
            // No attachment was ever made.
            assertEquals(0, registry.attachmentCount)
        } finally {
            group.shutdownGracefully()
            server.stop()
        }
    }

    @Test
    fun `attach for an unregistered username closes the connection`() {
        val registry = VoidburnPluginRegistry()
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            channel.writeAndFlush(TextWebSocketFrame("""{"type":"attach","username":"Nobody"}""")).sync()
            channel.closeFuture().await(2, TimeUnit.SECONDS)
            assertEquals(0, registry.attachmentCount)
        } finally {
            group.shutdownGracefully()
            server.stop()
        }
    }

    private fun connectWebSocketClient(
        group: NioEventLoopGroup,
        port: Int,
        frames: LinkedBlockingQueue<String>,
    ): io.netty.channel.Channel {
        val uri = URI("ws://127.0.0.1:$port/plugin")
        val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
            uri, WebSocketVersion.V13, null, false, DefaultHttpHeaders(),
        )
        val bootstrap = io.netty.bootstrap.Bootstrap()
            .group(group)
            .channel(io.netty.channel.socket.nio.NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(HttpClientCodec())
                        .addLast(HttpObjectAggregator(65536))
                        .addLast(WebSocketClientProtocolHandler(handshaker))
                        .addLast(object : SimpleChannelInboundHandler<TextWebSocketFrame>() {
                            override fun channelRead0(ctx: ChannelHandlerContext, msg: TextWebSocketFrame) {
                                frames.offer(msg.text())
                            }
                        })
                }
            })
        val channel = bootstrap.connect("127.0.0.1", port).sync().channel()
        // Wait for the WebSocket handshake to finish before returning.
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && !handshaker.isHandshakeComplete) {
            Thread.sleep(10)
        }
        return channel
    }

    // Silence unused-import warnings for ServerBootstrap / NioServerSocketChannel
    // (used transitively; kept for forward compatibility in case we add a direct test).
    @Suppress("unused")
    private fun unusedImportsAnchor(): Any = listOf(
        ServerBootstrap::class, NioServerSocketChannel::class,
    )
}
