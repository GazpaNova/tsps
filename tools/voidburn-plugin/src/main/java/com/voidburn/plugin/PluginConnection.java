package com.voidburn.plugin;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single OkHttp WebSocket connection to the gateway. Handles:
 *   - connecting to the configured URL
 *   - sending the attach message once the socket is open
 *   - delivering received text frames to a consumer
 *   - reconnecting with a bounded backoff when the connection drops
 *
 * A PluginConnection owns one URL/username/token triple. When the plugin's
 * config changes, the plugin stops the current connection and constructs a
 * new one; this class deliberately does not try to re-authenticate a live
 * socket with different credentials.
 *
 * Threading: OkHttp invokes WebSocketListener callbacks on its own dispatcher
 * thread. The frame consumer may land on any thread, so the caller is
 * responsible for marshalling into RuneLite's client thread before touching
 * the game state. reconnects are scheduled on a single-thread executor owned
 * by this instance; stop() cancels any pending reconnect synchronously.
 */
final class PluginConnection {
    private static final Logger log = LoggerFactory.getLogger(PluginConnection.class);

    /** Backoff for the first reconnect attempt after a failure. */
    private static final long INITIAL_BACKOFF_MS = 1_000L;

    /** Upper bound on backoff between reconnect attempts; keeps retries alive without hammering the gateway. */
    private static final long MAX_BACKOFF_MS = 30_000L;

    /** How often OkHttp sends a WebSocket ping frame. Must be below the gateway's idle timeout (not currently enforced, but future-proof). */
    private static final Duration PING_INTERVAL = Duration.ofSeconds(20);

    private final String url;
    private final String username;
    private final String token;
    private final Consumer<String> frameHandler;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService reconnectExecutor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile long nextBackoffMs = INITIAL_BACKOFF_MS;

    PluginConnection(String url, String username, String token, Consumer<String> frameHandler) {
        this.url = url;
        this.username = username;
        this.token = token;
        this.frameHandler = frameHandler;
        this.httpClient = new OkHttpClient.Builder()
            .readTimeout(Duration.ZERO) // no timeout — WebSocket is long-lived
            .pingInterval(PING_INTERVAL)
            .build();
        this.reconnectExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "voidburn-plugin-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the first connection attempt. Returns immediately; the WebSocket
     * handshake runs on OkHttp's dispatcher. Safe to call once per instance.
     */
    void start() {
        if (stopped.get()) return;
        connect();
    }

    /**
     * Close any live connection and refuse further reconnect attempts.
     * Idempotent; safe to call from any thread.
     */
    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.close(1000, "plugin shutting down");
        }
        reconnectExecutor.shutdownNow();
        // OkHttp's dispatcher keeps non-daemon threads alive for ~60s after the
        // last job. Nudging it along here lets RuneLite shut down promptly when
        // the plugin is disabled.
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    private void connect() {
        if (stopped.get()) return;
        Request request = new Request.Builder()
            .url(url)
            .build();
        log.debug("Voidburn plugin: connecting to {} as {}", url, username);
        webSocket = httpClient.newWebSocket(request, new Listener());
    }

    private void scheduleReconnect() {
        if (stopped.get()) return;
        long delay = nextBackoffMs;
        nextBackoffMs = Math.min(nextBackoffMs * 2, MAX_BACKOFF_MS);
        log.info("Voidburn plugin: reconnecting in {} ms", delay);
        try {
            reconnectExecutor.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stop() ran after we checked stopped.get(); fine to drop the retry.
        }
    }

    private final class Listener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            log.info("Voidburn plugin: connected, sending attach for {}", username);
            // First and only frame for Phase 2c-2a: the attach message. The
            // gateway accepts it or closes the connection; either way, future
            // frames arrive through onMessage.
            webSocket.send(AttachMessage.build(username, token));
            // Reset backoff on a healthy connection. We can't tell here whether
            // the attach authorized — a bad token will close the socket in
            // onClosed — but a successful TCP+WS handshake means the gateway
            // is reachable, so the next failure's backoff can start over.
            nextBackoffMs = INITIAL_BACKOFF_MS;
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            // Phase 2c-2a logs only. Phase 2c-2b adds the decoder; 2c-3 the
            // renderer.
            log.debug("Voidburn plugin: frame received: {}", text);
            try {
                frameHandler.accept(text);
            } catch (Throwable t) {
                // A handler crash must not take the socket down — log and move on.
                log.warn("Voidburn plugin: frame handler threw", t);
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            // Honour server-initiated close with a clean echo; onClosed follows.
            webSocket.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            log.info("Voidburn plugin: connection closed (code={}, reason={})", code, reason);
            PluginConnection.this.webSocket = null;
            scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            // Lower level than onClosed — connection refused, DNS failure, bad
            // protocol. Log and reconnect through the same backoff.
            log.info("Voidburn plugin: connection failed: {}", t.getMessage());
            PluginConnection.this.webSocket = null;
            scheduleReconnect();
        }
    }
}
