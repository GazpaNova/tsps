package gateway.plugin

import gateway.session.VoidburnPluginChannel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Integration-style tests that exercise the real Netty pipeline on a loopback port.
 *
 * Registry events are observed through a typed Event wrapper (not a nullable queue):
 * LinkedBlockingQueue throws NPE on offer(null), so a nullable queue in v2 silently
 * swallowed every detach notification and the tests passed by timeout rather than by
 * observing anything. Here, every attach and every detach surfaces as an Event value
 * and the test asserts the value, not the absence of one.
 *
 * Rejection tests assert that closeFuture().await() actually returned true (completed
 * within the deadline). A server that mistakenly kept the connection open would also
 * reach a blocking await() that timed out; checking the boolean catches that.
 */
class VoidburnPluginServerTest {
    private class NoopChannel : VoidburnPluginChannel {
        override fun send(message: String) {}
    }

    private sealed class Event {
        data class Attach(val channel: VoidburnPluginChannel) : Event()
        object Detach : Event()
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
        // A brand-new server on a fresh port must still bind cleanly.
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
        val events = LinkedBlockingQueue<Event>()
        registry.register("Miguel") { channel ->
            if (channel == null) events.offer(Event.Detach) else events.offer(Event.Attach(channel))
        }
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            channel.writeAndFlush(TextWebSocketFrame("""{"type":"attach","username":"Miguel"}""")).sync()
            val attached = events.poll(2, TimeUnit.SECONDS)
            assertTrue(attached is Event.Attach, "attach event should fire")
            // The channel announced by the registry is the PluginConnectionHandler
            // (the test doesn't need it further, but asserting it exists proves the
            // lambda's non-null branch ran).
            val bound = (attached as Event.Attach).channel
            assertFalse(bound is NoopChannel, "bound channel should be the server handler, not a test noop")
            // Close the client. Server detaches, registry fires null callback → Detach event.
            val closed = channel.close().await(2, TimeUnit.SECONDS)
            assertTrue(closed, "client close should complete")
            val detached = events.poll(2, TimeUnit.SECONDS)
            assertSame(Event.Detach, detached, "detach event should fire after close")
            assertEquals(0, registry.attachmentCount)
        } finally {
            group.shutdownGracefully()
            server.stop()
        }
    }

    @Test
    fun `malformed attach closes the connection and leaves registry empty`() {
        val registry = VoidburnPluginRegistry()
        val events = LinkedBlockingQueue<Event>()
        registry.register("Miguel") { channel ->
            if (channel == null) events.offer(Event.Detach) else events.offer(Event.Attach(channel))
        }
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
            // Server closes the connection — closeFuture() must COMPLETE within the
            // deadline. Previously we ignored the return value; a hung connection
            // would pass a timing-based "assertion" by accident.
            val closed = channel.closeFuture().await(2, TimeUnit.SECONDS)
            assertTrue(closed, "server should close the connection on malformed attach")
            // No attach event fired: the registry was never touched.
            val attached = events.poll(200, TimeUnit.MILLISECONDS)
            assertEquals(null, attached)
            assertEquals(0, registry.attachmentCount)
        } finally {
            group.shutdownGracefully()
            server.stop()
        }
    }

    @Test
    fun `attach for an unregistered username closes the connection`() {
        val registry = VoidburnPluginRegistry()
        val events = LinkedBlockingQueue<Event>()
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            channel.writeAndFlush(TextWebSocketFrame("""{"type":"attach","username":"Nobody"}""")).sync()
            val closed = channel.closeFuture().await(2, TimeUnit.SECONDS)
            assertTrue(closed, "server should close the connection when no session matches")
            val attached = events.poll(200, TimeUnit.MILLISECONDS)
            assertEquals(null, attached)
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
        assertTrue(handshaker.isHandshakeComplete, "WebSocket handshake should complete")
        return channel
    }

}
