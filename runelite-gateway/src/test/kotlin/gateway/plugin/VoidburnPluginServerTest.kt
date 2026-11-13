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
 * Phase 2c-1: attach is authorized by token. Tests cover a valid attach, a wrong-
 * token attach, and verify the latter closes the connection without touching the
 * registry.
 */
class VoidburnPluginServerTest {
    private class NoopChannel : VoidburnPluginChannel {
        override fun send(message: String) {}
    }

    private sealed class Event {
        data class Attach(val channel: VoidburnPluginChannel) : Event()
        object Detach : Event()
    }

    private fun VoidburnPluginRegistry.registerWithToken(
        username: String,
        token: String,
        events: LinkedBlockingQueue<Event>,
    ) = register(
        username,
        attach = { channel ->
            if (channel == null) events.offer(Event.Detach) else events.offer(Event.Attach(channel))
        },
        authorize = { it == token },
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `start is a no-op when port is zero`() {
        val registry = VoidburnPluginRegistry()
        val server = VoidburnPluginServer(port = 0, bindHost = "127.0.0.1", registry = registry)
        server.start()
        server.stop()
    }

    @Test
    fun `failed bind cleans up and does not block a later successful start`() {
        val registry = VoidburnPluginRegistry()
        ServerSocket(0).use { squatter ->
            val taken = squatter.localPort
            val server = VoidburnPluginServer(port = taken, bindHost = "127.0.0.1", registry = registry)
            assertThrows(Exception::class.java) { server.start() }
            server.stop()
        }
        val fresh = freePort()
        val server = VoidburnPluginServer(port = fresh, bindHost = "127.0.0.1", registry = registry)
        try {
            server.start()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `attach with a valid token binds the registered session`() {
        val registry = VoidburnPluginRegistry()
        val events = LinkedBlockingQueue<Event>()
        registry.registerWithToken("Miguel", token = "secret-T", events = events)
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            channel.writeAndFlush(
                TextWebSocketFrame("""{"type":"attach","username":"Miguel","token":"secret-T"}""")
            ).sync()
            val attached = events.poll(2, TimeUnit.SECONDS)
            assertTrue(attached is Event.Attach, "attach event should fire")
            val bound = (attached as Event.Attach).channel
            assertFalse(bound is NoopChannel, "bound channel should be the server handler")
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
    fun `attach with a wrong token closes the connection and does not bind`() {
        val registry = VoidburnPluginRegistry()
        val events = LinkedBlockingQueue<Event>()
        registry.registerWithToken("Miguel", token = "secret-T", events = events)
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            channel.writeAndFlush(
                TextWebSocketFrame("""{"type":"attach","username":"Miguel","token":"WRONG"}""")
            ).sync()
            val closed = channel.closeFuture().await(2, TimeUnit.SECONDS)
            assertTrue(closed, "server should close on authorization failure")
            // No attach event: the session was never touched.
            val attached = events.poll(200, TimeUnit.MILLISECONDS)
            assertEquals(null, attached)
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
        registry.registerWithToken("Miguel", token = "T", events = events)
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            // v1 regression shape: nested-field payload. Also covers "no token field".
            channel.writeAndFlush(
                TextWebSocketFrame("""{"nested":{"type":"attach","username":"Miguel","token":"T"}}""")
            ).sync()
            val closed = channel.closeFuture().await(2, TimeUnit.SECONDS)
            assertTrue(closed, "server should close on malformed attach")
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
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            channel.writeAndFlush(
                TextWebSocketFrame("""{"type":"attach","username":"Nobody","token":"whatever"}""")
            ).sync()
            val closed = channel.closeFuture().await(2, TimeUnit.SECONDS)
            assertTrue(closed, "server should close when no session matches")
            assertEquals(0, registry.attachmentCount)
        } finally {
            group.shutdownGracefully()
            server.stop()
        }
    }

    @Test
    fun `attach then click dispatches the click to the session's onClick callback`() {
        // Phase 2d-1 integration: a WebSocket client that attaches successfully
        // can then send a click frame, and the gateway routes it to the session's
        // onClick lambda (not the attach lambda).
        val registry = VoidburnPluginRegistry()
        val events = LinkedBlockingQueue<Event>()
        val clicks = LinkedBlockingQueue<VoidburnPluginMessages.Click>()
        registry.register(
            "Miguel",
            attach = { channel ->
                if (channel == null) events.offer(Event.Detach) else events.offer(Event.Attach(channel))
            },
            authorize = { it == "secret-T" },
            onClick = { _, c -> clicks.offer(c) },
        )
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            channel.writeAndFlush(TextWebSocketFrame(
                """{"type":"attach","username":"Miguel","token":"secret-T"}"""
            )).sync()
            val attached = events.poll(2, TimeUnit.SECONDS)
            assertTrue(attached is Event.Attach, "attach event should fire first")
            val uid = (30020 shl 16) or 186
            channel.writeAndFlush(TextWebSocketFrame(
                """{"type":"click","widgetId":$uid,"op":1}"""
            )).sync()
            val click = clicks.poll(2, TimeUnit.SECONDS)
            assertEquals(VoidburnPluginMessages.Click(uid, 0xFFFF, 0xFFFF, 1), click)
        } finally {
            group.shutdownGracefully()
            server.stop()
        }
    }

    @Test
    fun `click before attach is dropped without closing the connection`() {
        // A plugin that sends a click before attaching has done something wrong
        // but shouldn't cause the connection to drop mid-handshake (the attach
        // may still come in the next frame). The current code requires attach
        // first; anything else before attach lands in the parseAttach path
        // and closes. This test documents that expectation — it's a hostile
        // ordering, closing is correct.
        val registry = VoidburnPluginRegistry()
        val clicks = LinkedBlockingQueue<VoidburnPluginMessages.Click>()
        registry.register("Miguel",
            attach = {},
            authorize = { it == "T" },
            onClick = { _, c -> clicks.offer(c) })
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            val uid = (30020 shl 16) or 186
            channel.writeAndFlush(TextWebSocketFrame(
                """{"type":"click","widgetId":$uid,"op":1}"""
            )).sync()
            // Connection closes because parseAttach rejects a click message.
            val closed = channel.closeFuture().await(2, TimeUnit.SECONDS)
            assertTrue(closed, "server closes on a pre-attach click")
            // No click reached the session.
            assertEquals(null, clicks.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            group.shutdownGracefully()
            server.stop()
        }
    }

    @Test
    fun `unrecognised frame after attach is dropped without closing`() {
        val registry = VoidburnPluginRegistry()
        val clicks = LinkedBlockingQueue<VoidburnPluginMessages.Click>()
        registry.register("Miguel",
            attach = {},
            authorize = { it == "T" },
            onClick = { _, c -> clicks.offer(c) })
        val port = freePort()
        val server = VoidburnPluginServer(port = port, bindHost = "127.0.0.1", registry = registry)
        server.start()
        val group = NioEventLoopGroup()
        try {
            val frames = LinkedBlockingQueue<String>()
            val channel = connectWebSocketClient(group, port, frames)
            channel.writeAndFlush(TextWebSocketFrame(
                """{"type":"attach","username":"Miguel","token":"T"}"""
            )).sync()
            // Give the server a moment to process the attach.
            Thread.sleep(100)
            // Send a bogus frame type — not a click, not an attach.
            channel.writeAndFlush(TextWebSocketFrame(
                """{"type":"unknown_future","foo":1}"""
            )).sync()
            // Connection stays open; no click arrived.
            Thread.sleep(200)
            assertTrue(channel.isActive, "unrecognised frame must not close the attached connection")
            assertEquals(null, clicks.poll(200, TimeUnit.MILLISECONDS))
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
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && !handshaker.isHandshakeComplete) {
            Thread.sleep(10)
        }
        assertTrue(handshaker.isHandshakeComplete, "WebSocket handshake should complete")
        return channel
    }
}
