package com.cypator.ordertester.ws;

import com.cypator.ordertester.config.TesterConfig.SessionConfig;
import com.cypator.ordertester.config.TesterConfig.WsConfig;
import com.cypator.ordertester.util.Templates;
import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One logged-on WebSocket session (the API has two per client: market and trade).
 *
 * Owns the socket lifecycle: connect, logon (HMAC signature), keep-alive pings, reconnect and
 * deliberate replacement of the socket. Messages from a socket that has been replaced never
 * reach {@link Handler#onMessage}, so stale replies cannot touch live state.
 */
public final class WsSession implements AutoCloseable {
    static final long PING_QUIET_MS = 1_000;
    /** The API drops a session without a ping for 30 s: ping even during a long order flight once this is reached. */
    static final long PING_HARD_DEADLINE_MS = 25_000;

    public interface Handler {
        /** A non-heartbeat, non-logon message on the current, logged-on socket (socket thread). */
        void onMessage(JsonNode msg, String text);

        /** A message that arrived on a socket this session already replaced. */
        default void onStaleMessage(JsonNode msg, String text) {
        }

        /** The current socket was closed by the server or failed (not called for sockets we replaced/closed). */
        void onLost(String why);
    }

    private final String name;
    private final WsConfig ws;
    private final SessionConfig sc;
    private final Handler handler;
    private HttpClient http;
    private volatile Connection conn;
    private ScheduledExecutorService pinger;
    private volatile boolean closing;

    /** Pings and "flights" (an order waiting for its answer) never overlap. */
    private final Object flightLock = new Object();
    private boolean inFlight;
    /** Last ping actually sent (the quiet window before an order is measured from this). */
    private long lastPingNanos;
    /** Last ping or logon (the 30 s keep-alive deadline is measured from this). */
    private long keepAliveNanos;

    public WsSession(String name, WsConfig ws, SessionConfig sc, Handler handler) {
        this.name = name;
        this.ws = ws;
        this.sc = sc;
        this.handler = handler;
    }

    public String name() {
        return name;
    }

    public String url() {
        return sc.url;
    }

    private String apiKey() {
        return sc.apiKey != null ? sc.apiKey : ws.apiKey;
    }

    private String secret() {
        return sc.secret != null ? sc.secret : ws.secret;
    }

    private String server() {
        return sc.server != null ? sc.server : ws.server;
    }

    /** Everything that can be checked without a socket. Throws IllegalArgumentException (config error). */
    public void validate() {
        String url = Templates.require(sc.url, "ws." + name + ".url");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ws." + name + ".url is not a valid URI: " + url);
        }
        if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("ws") || uri.getScheme().equalsIgnoreCase("wss"))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("ws." + name + ".url must be ws://host[:port]/path or wss://..., got: " + url);
        }
        String logon = ws.logonTemplate == null ? "" : ws.logonTemplate.toString();
        if (logon.contains("${apiKey}")) Templates.require(apiKey(), "ws.apiKey (or ws." + name + ".apiKey)");
        if (logon.contains("${sign}")) Templates.require(secret(), "ws.secret (or ws." + name + ".secret)");
        if (logon.contains("${server}")) Templates.require(server(), "ws.server (or ws." + name + ".server)");
        if (logon.contains("${client}")) Templates.require(sc.client, "ws." + name + ".client");
        ws.headers.forEach((k, v) -> Templates.require(v, "ws.headers." + k));
        sc.headers.forEach((k, v) -> Templates.require(v, "ws." + name + ".headers." + k));
        if (ws.logonTimeoutMs <= 0) throw new IllegalArgumentException("ws.logonTimeoutMs must be > 0");
        boolean local = uri.getHost().equals("localhost") || uri.getHost().startsWith("127.");
        if (!local && ws.pingIntervalMs > 0 && ws.pingIntervalMs < 15_000) {
            throw new IllegalArgumentException("ws.pingIntervalMs=" + ws.pingIntervalMs
                    + " breaks the API rate limit (max 1 ping / 15 s; the server drops the connection)");
        }
        if (!local && (ws.pingIntervalMs <= 0 || ws.pingIntervalMs > PING_HARD_DEADLINE_MS)) {
            throw new IllegalArgumentException("ws.pingIntervalMs=" + ws.pingIntervalMs
                    + " is too long: the API drops a session that sends no ping for 30 s (use 15000-25000)");
        }
    }

    public void connect() throws Exception {
        validate();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(ws.logonTimeoutMs)).build();
        conn = open();
        startPinger();
    }

    private Connection open() throws Exception {
        Connection c = new Connection();
        WebSocket.Builder b = http.newWebSocketBuilder().connectTimeout(Duration.ofMillis(ws.logonTimeoutMs));
        ws.headers.forEach(b::header);
        sc.headers.forEach(b::header);
        log("connecting " + sc.url + (sc.client == null ? "" : " as client " + sc.client));
        try {
            c.ws = b.buildAsync(URI.create(sc.url), c).get(ws.logonTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException(name + " session: connect failed: " + rootMessage(e)
                    + " (check the URL, TLS/port, and that your IP is whitelisted on the NLB)");
        } catch (TimeoutException e) {
            throw new IllegalStateException(name + " session: connect timed out after " + ws.logonTimeoutMs + " ms");
        }

        if (ws.logonTemplate != null && !ws.logonTemplate.isNull()) {
            c.send(Templates.renderToString(ws.logonTemplate, logonVars(), ws.dropNullFields), false);
            log(">> logon (apiKey " + mask(apiKey()) + ", server " + server() + ", client " + sc.client + ", sign ***)");
            String err;
            try {
                err = c.logon.get(ws.logonTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                c.retire();
                throw new IllegalStateException(name + " session: logon not answered within " + ws.logonTimeoutMs
                        + " ms (wrong logonTemplate fields? see ws.logonOps)");
            }
            if (err != null) {
                c.retire();
                throw new IllegalStateException(name + " session: logon failed: " + err);
            }
            log("logged on");
        }
        synchronized (flightLock) {
            keepAliveNanos = System.nanoTime(); // the 30 s keep-alive clock starts at logon
        }
        return c;
    }

    /** Reconnect (and re-logon) if the current socket is gone. Returns true when it actually reconnected. */
    public boolean ensureConnected() throws Exception {
        Connection c = conn;
        if (c != null && c.isOpen()) return false;
        String why = c == null ? "replaced" : c.closeReason;
        if (!ws.reconnect) throw new IllegalStateException(name + " session is closed (" + why + ") and ws.reconnect=false");
        log("socket is closed (" + why + ") - reconnecting");
        conn = open();
        return true;
    }

    /**
     * Drop the current socket so that nothing still in flight on it can be attributed to later work.
     * The next {@link #ensureConnected()} opens a fresh one.
     */
    public void recycle(String reason) {
        Connection old = conn;
        if (old == null) return;
        if (!ws.reconnect) {
            log("WARNING: " + reason + "; ws.reconnect=false, so the socket is kept and a late reply"
                    + " without clOrderId could be credited to the next order");
            return;
        }
        log(reason + " - replacing the socket so a late reply cannot be credited to the next order");
        conn = null;
        old.retire();
    }

    public boolean isOpen() {
        Connection c = conn;
        return c != null && c.isOpen();
    }

    public void send(String json, boolean echo) throws Exception {
        Connection c = conn;
        if (c == null) throw new IllegalStateException(name + " session is not connected");
        c.send(json, echo);
    }

    /** Start an exclusive "flight": waits until at least PING_QUIET_MS after the last ping, then blocks pings. */
    public void beginFlight() throws InterruptedException {
        synchronized (flightLock) {
            long sincePingMs = (System.nanoTime() - lastPingNanos) / 1_000_000;
            if (lastPingNanos != 0 && sincePingMs < PING_QUIET_MS) Thread.sleep(PING_QUIET_MS - sincePingMs);
            inFlight = true;
        }
    }

    public void endFlight() {
        synchronized (flightLock) {
            inFlight = false;
        }
    }

    private void startPinger() {
        if (ws.pingTemplate == null || ws.pingTemplate.isNull() || ws.pingIntervalMs <= 0) return;
        pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-ping-" + name);
            t.setDaemon(true);
            return t;
        });
        long tick = Math.max(10, Math.min(1_000, ws.pingIntervalMs / 4));
        // Deadline-driven: a tick skipped during an order flight is caught up by the next one.
        pinger.scheduleWithFixedDelay(() -> {
            synchronized (flightLock) {
                Connection c = conn;
                if (c == null || !c.isOpen()) return;
                long sinceMs = (System.nanoTime() - keepAliveNanos) / 1_000_000;
                if (sinceMs < ws.pingIntervalMs) return;
                // Normally never ping while an order waits for its answer (a ping error would land on the order),
                // but never let the session reach the 30 s drop either.
                if (inFlight && sinceMs < PING_HARD_DEADLINE_MS) return;
                try {
                    Map<String, Object> v = new HashMap<>();
                    v.put("nowMs", System.currentTimeMillis());
                    v.put("timestamp", timestamp());
                    c.send(Templates.renderToString(ws.pingTemplate, v, true), false);
                    lastPingNanos = System.nanoTime();
                    keepAliveNanos = lastPingNanos;
                } catch (Exception e) {
                    log("ping failed: " + e.getMessage());
                }
            }
        }, tick, tick, TimeUnit.MILLISECONDS);
    }

    private Map<String, Object> logonVars() throws Exception {
        long ts = timestamp();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("apiKey", apiKey() == null ? "" : apiKey());
        v.put("server", server() == null ? "" : server());
        v.put("client", sc.client == null ? "" : sc.client);
        v.put("timestamp", ts);
        v.put("nowMs", System.currentTimeMillis());
        String secret = secret();
        v.put("sign", secret == null || secret.isEmpty() ? "" : sign(ts + ws.signPath, secret));
        return v;
    }

    private long timestamp() {
        long now = System.currentTimeMillis();
        return "s".equalsIgnoreCase(ws.timestampUnit) ? now / 1000 : now;
    }

    /** Base64(HMAC-SHA256(payload, secret)). */
    public static String sign(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------- reply helpers shared by handlers

    public String op(JsonNode msg) {
        return Templates.at(msg, ws.opPointer);
    }

    public String code(JsonNode msg) {
        return Templates.at(msg, ws.codePointer);
    }

    public boolean codeOk(JsonNode msg) {
        String code = code(msg);
        return code == null || ws.okCodes.contains(code);
    }

    /** "code 50202: Unknown symbol", "INVALID_TIF", or the op name. */
    public String errorText(JsonNode msg) {
        String code = code(msg);
        String err = Templates.at(msg, ws.errorPointer);
        String t = (code == null ? "" : "code " + code) + (code != null && err != null ? ": " : "") + (err == null ? "" : err);
        if (t.isEmpty()) {
            String op = op(msg);
            t = op == null ? "error reply" : op;
        }
        return t;
    }

    public static boolean containsIgnoreCase(List<String> list, String value) {
        if (value == null) return false;
        for (String s : list) if (s.equalsIgnoreCase(value)) return true;
        return false;
    }

    // ---------------------------------------------------------------- socket

    private void dispatch(Connection c, String text) {
        JsonNode msg;
        try {
            msg = Templates.MAPPER.readTree(text);
        } catch (Exception e) {
            log("<< unparseable: " + text);
            return;
        }
        String op = op(msg);
        if (containsIgnoreCase(ws.heartbeatOps, op)) return; // the API answers a ping with {"op":"ping"}
        if (!containsIgnoreCase(ws.quietOps, op)) log("<< " + text);

        if (containsIgnoreCase(ws.logonOps, op)) {
            c.logon.complete(codeOk(msg) ? null : errorText(msg));
            return;
        }
        if (!c.logon.isDone()) {
            // Before logon completes, any error reply is the answer to the logon.
            boolean isError = !codeOk(msg) || containsIgnoreCase(List.of("invalid", "error", "reject"), op);
            if (isError) c.logon.complete(errorText(msg));
            return;
        }
        if (c != conn) {
            handler.onStaleMessage(msg, text);
            log("   (message on a replaced socket - recorded only)");
            return;
        }
        handler.onMessage(msg, text);
    }

    private final class Connection implements WebSocket.Listener {
        volatile WebSocket ws;
        final CompletableFuture<String> logon = new CompletableFuture<>();
        volatile boolean closed;
        volatile boolean retired;
        volatile String closeReason = "";
        private final StringBuilder buf = new StringBuilder();

        Connection() {
            if (WsSession.this.ws.logonTemplate == null || WsSession.this.ws.logonTemplate.isNull()) logon.complete(null);
        }

        boolean isOpen() {
            WebSocket w = ws;
            return !closed && !retired && w != null && !w.isOutputClosed() && !w.isInputClosed();
        }

        synchronized void send(String json, boolean echo) throws Exception {
            WebSocket w = ws;
            if (w == null || closed || retired || w.isOutputClosed()) {
                throw new IllegalStateException(name + " socket is closed" + (closeReason.isEmpty() ? "" : " (" + closeReason + ")"));
            }
            if (echo) log(">> " + redact(json));
            w.sendText(json, true).get(10, TimeUnit.SECONDS);
        }

        /** Stop using this socket; its callbacks no longer affect anything. */
        void retire() {
            retired = true;
            closeReason = "replaced";
            WebSocket w = ws;
            if (w != null) w.abort();
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                try {
                    dispatch(this, text);
                } catch (Exception e) {
                    log("error handling message: " + e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            lost("closed by server: " + statusCode + (reason == null || reason.isEmpty() ? "" : " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            lost("socket error: " + rootMessage(error));
        }

        private void lost(String why) {
            closed = true;
            if (!retired) closeReason = why;
            if (closing || retired) return;
            log(why);
            logon.complete(why);
            if (conn == this) handler.onLost(why);
        }
    }

    private static String mask(String key) {
        if (key == null || key.isEmpty()) return "-";
        return key.length() <= 4 ? "***" : key.substring(0, Math.min(4, key.length() / 2)) + "***";
    }

    private String redact(String json) {
        String out = json;
        String key = apiKey();
        if (key != null && !key.isEmpty()) out = out.replace(key, mask(key));
        return out;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.toString() : r.getClass().getSimpleName() + ": " + r.getMessage();
    }

    void log(String s) {
        System.out.println("[" + name + "] " + s);
    }

    @Override
    public void close() {
        closing = true;
        if (pinger != null) pinger.shutdownNow();
        Connection c = conn;
        if (c != null && c.ws != null && !c.ws.isOutputClosed()) {
            try {
                c.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                c.ws.abort();
            }
        }
    }
}
