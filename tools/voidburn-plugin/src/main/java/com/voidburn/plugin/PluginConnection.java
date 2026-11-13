package com.voidburn.plugin;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * Lifecycle state (webSocket, stopped flag, stabilityTask, attemptId,
 * connectionOpen) is guarded by a single monitor. stop() acquires it before
 * tearing down; connect(), onOpen, onClosed and onFailure each acquire it
 * around their check-publish-send sequence, so an operation cannot pass its
 * check and execute after a concurrent teardown committed. Per-attempt
 * identity (attemptId) lets the stability task self-guard at run time: if
 * schedule()'s return assignment races with a close callback, the task
 * still refuses to run if its attempt is no longer current and open.
 *
 * Threading: OkHttp invokes WebSocketListener callbacks on its own dispatcher
 * thread. The frame consumer may land on any thread, so the caller is
 * responsible for marshalling into RuneLite's client thread before touching
 * the game state. Reconnects and stability confirmations are scheduled on a
 * single-thread executor owned by this instance; stop() cancels any pending
 * tasks synchronously under the lock.
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

    /** Guards every mutation of the fields below. See class javadoc. */
    private final Object lock = new Object();

    /** All state below is guarded by `lock`. */
    private boolean stopped = false;
    private WebSocket webSocket;
    private long nextBackoffMs = INITIAL_BACKOFF_MS;
    /** Monotonic ID assigned to each connect() call. Listeners capture their ID and callbacks compare before acting. */
    private long attemptId = 0;
    /** True between a Listener's onOpen and its matching onClosed/onFailure. The stability task checks this to avoid reseting after a closed-before-stable attempt. */
    private boolean connectionOpen = false;
    /** Scheduled stability-confirmation task for the current attempt. Cancelled on close or stop. */
    private ScheduledFuture<?> stabilityTask;

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
        connect();
    }

    /**
     * Close any live connection and refuse further reconnect attempts.
     * Idempotent; safe to call from any thread.
     */
    void stop() {
        WebSocket wsToClose;
        synchronized (lock) {
            if (stopped) return;
            stopped = true;
            // Advance the attempt counter so any pending callback — one that
            // already captured an older ID — sees a mismatch and no-ops when
            // it later takes the lock.
            attemptId++;
            connectionOpen = false;
            if (stabilityTask != null) {
                stabilityTask.cancel(false);
                stabilityTask = null;
            }
            wsToClose = webSocket;
            webSocket = null;
        }
        if (wsToClose != null) {
            wsToClose.close(1000, "plugin shutting down");
        }
        reconnectExecutor.shutdownNow();
        // OkHttp's dispatcher keeps non-daemon threads alive for ~60s after the
        // last job. Nudging it along here lets RuneLite shut down promptly when
        // the plugin is disabled.
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    private void connect() {
        Listener listener;
        long myAttempt;
        synchronized (lock) {
            if (stopped) return;
            myAttempt = ++attemptId;
            listener = new Listener(myAttempt);
            log.debug("Voidburn plugin: connecting to {} as {} (attempt {})", url, username, myAttempt);
        }
        // newWebSocket is non-blocking (returns immediately; handshake runs on
        // OkHttp's dispatcher), but it allocates native resources and we want
        // its return value stored atomically with the up-to-date attempt
        // check. Re-enter the lock for publication.
        Request request = new Request.Builder().url(url).build();
        WebSocket ws = httpClient.newWebSocket(request, listener);
        boolean obsolete;
        synchronized (lock) {
            obsolete = stopped || myAttempt != attemptId;
            if (!obsolete) {
                webSocket = ws;
            }
        }
        if (obsolete) {
            ws.close(1000, "stopped during connect");
        }
    }

    private void scheduleStabilityConfirmation(long forAttempt) {
        // Called from onOpen while holding `lock`. We stay inside the lock to
        // assign the returned future, so cancelStabilityConfirmation sees it.
        try {
            stabilityTask = reconnectExecutor.schedule(
                () -> runStabilityConfirmation(forAttempt),
                STABILITY_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stop() ran; nothing to do.
        }
    }

    private void runStabilityConfirmation(long forAttempt) {
        synchronized (lock) {
            // Self-guard: only reset backoff if the attempt that scheduled us
            // is STILL the current one AND the connection is still open. If
            // onClosed/onFailure already ran (even if its cancel raced with
            // our publication), connectionOpen is false and we no-op.
            if (!stopped && connectionOpen && forAttempt == attemptId) {
                nextBackoffMs = INITIAL_BACKOFF_MS;
                log.debug("Voidburn plugin: connection stable; backoff reset");
            }
        }
    }

    private void cancelStabilityConfirmation() {
        // Caller holds `lock`.
        if (stabilityTask != null) {
            stabilityTask.cancel(false);
            stabilityTask = null;
        }
    }

    private void scheduleReconnect() {
        // Caller holds `lock`.
        if (stopped) return;
        long delay = nextBackoffMs;
        nextBackoffMs = Math.min(nextBackoffMs * 2, MAX_BACKOFF_MS);
        log.info("Voidburn plugin: reconnecting in {} ms", delay);
        try {
            reconnectExecutor.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stop() ran after we checked stopped; fine to drop the retry.
        }
    }

    private final class Listener extends WebSocketListener {
        private final long myAttempt;

        Listener(long myAttempt) {
            this.myAttempt = myAttempt;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            // The decision and the send must be atomic with respect to stop():
            // if we release the lock between the stopped check and send(), a
            // concurrent stop() can commit its state change in that window and
            // we leak the attach onto a connection the owner has torn down.
            // send() is also safe to call under the lock — OkHttp's
            // WebSocket.send enqueues and returns without invoking any
            // callback synchronously, so there's no risk of a re-entering
            // listener callback blocking on `lock`.
            //
            // This holds regardless of whether the socket was already
            // published into `webSocket` (the normal path) or not
            // (OkHttp dispatcher pattern where the listener runs before
            // newWebSocket returns): if stop() ran first, it set stopped=true
            // and bumped attemptId, so this method sees the gate closed and
            // closes the socket it was handed instead of sending.
            boolean doSend;
            synchronized (lock) {
                doSend = !stopped && myAttempt == attemptId;
                if (doSend) {
                    connectionOpen = true;
                    scheduleStabilityConfirmation(myAttempt);
                    log.info(
                        "Voidburn plugin: connected, sending attach for {} (attempt {})",
                        username, myAttempt);
                    ws.send(AttachMessage.build(username, token));
                }
            }
            if (!doSend) {
                log.debug(
                    "Voidburn plugin: onOpen for obsolete attempt {} (current {}); closing",
                    myAttempt, attemptId);
                // Close outside the lock: OkHttp's close may invoke onClosed
                // on this same dispatcher thread synchronously in some paths,
                // and that callback takes `lock`.
                ws.close(1000, "obsolete or stopped");
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            // The obsolete-attempt check AND the frameHandler invocation run under
            // the same lock so a concurrent stop() cannot slip between them. The
            // handler for Phase 2c-2b is a pure decode + a ConcurrentHashMap write
            // (InterfaceState.apply) — non-blocking and non-reentrant into this
            // connection, so holding `lock` across it is safe. A later overlay that
            // marshals to the RuneLite client thread must still schedule, not
            // execute, under this lock.
            synchronized (lock) {
                if (stopped || myAttempt != attemptId) return;
                log.debug("Voidburn plugin: frame received: {}", text);
                try {
                    frameHandler.accept(text);
                } catch (Throwable t) {
                    log.warn("Voidburn plugin: frame handler threw", t);
                }
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            // Honour server-initiated close with a clean echo; onClosed follows.
            ws.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            handleTermination("closed (code=" + code + ", reason=" + reason + ")");
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            handleTermination("failed: " + t.getMessage());
        }

        private void handleTermination(String why) {
            boolean iAmCurrent;
            synchronized (lock) {
                iAmCurrent = !stopped && myAttempt == attemptId;
                if (iAmCurrent) {
                    log.info("Voidburn plugin: connection {}", why);
                    connectionOpen = false;
                    cancelStabilityConfirmation();
                    webSocket = null;
                    scheduleReconnect();
                } else {
                    // Obsolete callback — stop() or a newer connect() took
                    // over. Log at debug and don't touch state.
                    log.debug("Voidburn plugin: termination for obsolete attempt {}: {}", myAttempt, why);
                }
            }
        }
    }
}
