package com.voidburn.plugin;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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

    /**
     * How long a connection must stay open after the handshake before we treat
     * it as a sign of health and reset the backoff. Long enough to span a
     * typical attach rejection round trip (upgrade, send attach, server closes
     * on auth failure, local onClosed fires), so a wrong-token loop does NOT
     * keep resetting its retry timer. 10 seconds leaves plenty of headroom.
     */
    private static final Duration STABILITY_WINDOW = Duration.ofSeconds(10);

    private final String url;
    private final String username;
    private final String token;
    private final Consumer<String> frameHandler;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService reconnectExecutor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile long nextBackoffMs = INITIAL_BACKOFF_MS;
    /** Scheduled stability-confirmation task; cancelled on close or stop. */
    private volatile ScheduledFuture<?> stabilityTask;

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
        ScheduledFuture<?> st = stabilityTask;
        if (st != null) st.cancel(false);
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
        WebSocket ws = httpClient.newWebSocket(request, new Listener());
        // Fence against a stop() that ran between the stopped.get() check above
        // and now: if we lost the race, close the socket we just created rather
        // than publishing it into `webSocket` where a late onOpen could run
        // against a connection the owner thinks is dead.
        if (stopped.get()) {
            ws.close(1000, "stopped during connect");
            return;
        }
        webSocket = ws;
    }

    private void scheduleStabilityConfirmation() {
        if (stopped.get()) return;
        try {
            stabilityTask = reconnectExecutor.schedule(() -> {
                // If we're still open (no onClosed/onFailure cancelled us in
                // the meantime) and not stopped, the connection has lived past
                // the window — treat as healthy and reset backoff.
                if (!stopped.get()) {
                    nextBackoffMs = INITIAL_BACKOFF_MS;
                    log.debug("Voidburn plugin: connection stable; backoff reset");
                }
            }, STABILITY_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stop() ran; nothing to do.
        }
    }

    private void cancelStabilityConfirmation() {
        ScheduledFuture<?> st = stabilityTask;
        if (st != null) st.cancel(false);
        stabilityTask = null;
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
            // Fence against a stop() that ran during the handshake. If the
            // connection was torn down before onOpen landed, close the socket
            // and do nothing — sending attach would leak credentials onto a
            // connection the owner already considers dead.
            if (stopped.get()) {
                webSocket.close(1000, "stopped before attach");
                return;
            }
            log.info("Voidburn plugin: connected, sending attach for {}", username);
            // Send attach. The gateway accepts it (and starts forwarding
            // widget frames) or closes the connection on auth failure; both
            // land in onClosed/onFailure below.
            webSocket.send(AttachMessage.build(username, token));
            // Do NOT reset backoff here. A wrong token still gets a successful
            // WS handshake before the server closes on auth failure; resetting
            // here would make bad-token retries loop at 1s forever instead of
            // climbing to the 30s cap. Backoff is reset only after the
            // connection has stayed open past STABILITY_WINDOW — see
            // scheduleStabilityConfirmation().
            scheduleStabilityConfirmation();
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
            cancelStabilityConfirmation();
            PluginConnection.this.webSocket = null;
            scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            // Lower level than onClosed — connection refused, DNS failure, bad
            // protocol. Log and reconnect through the same backoff.
            log.info("Voidburn plugin: connection failed: {}", t.getMessage());
            cancelStabilityConfirmation();
            PluginConnection.this.webSocket = null;
            scheduleReconnect();
        }
    }
}
