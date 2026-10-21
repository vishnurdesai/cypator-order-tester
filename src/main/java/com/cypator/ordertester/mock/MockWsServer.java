package com.cypator.ordertester.mock;

import com.cypator.ordertester.ws.WsSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local stand-in for the Ripple/Cypator WS Taker API (plain ws://, RFC 6455 text frames):
 *
 *   java -cp target/cypator-order-tester.jar com.cypator.ordertester.mock.MockWsServer [port] [apiKey] [secret]
 *
 * Path /market = market session, /trade = trade session; both log on with apiKey + HMAC sign + server + client.
 * <ul>
 *   <li>market: subscribe -> ack (code 0), then a snapshot every 200 ms. Books: XRP/USD 2.40/2.41,
 *       USDC/USD 0.9998/1.0002, RLUSD/USD 0.9997/1.0003, EMPTY/SYM (no levels). Other symbols: 50202 Unknown symbol.</li>
 *   <li>trade: order -> echo with orderId and code 0, then a trade. Fills follow the book: MARKET at the touch;
 *       LIMIT if it crosses (BUY >= ask, SELL <= bid); otherwise IOC/FOK -> CANCELED and GOOD_TILL_CANCEL rests.
 *       USDC/USD above 500 is thin (IOC partially fills 500, FOK is killed).</li>
 *   <li>preExec=true fills in full at the order price.</li>
 *   <li>cancel_order by clOrderId or orderId -> trade CANCELED; unknown -> cancel_order code 50400.</li>
 *   <li>special symbols: BAD/SYM -> {"op":"invalid","arg":{"code":50400}}; SILENT/SYM -> no answer;
 *       DROP/SYM -> socket closed; SLOW/SYM -> uncorrelated error after 600 ms;
 *       ERRCL/SYM -> op=reject with its clOrderId and no code; DELAY/SYM -> FILLED after 600 ms.</li>
 *   <li>ping -> {"op":"ping"} (as the real API does).</li>
 * </ul>
 */
public final class MockWsServer implements AutoCloseable {
    private static final ObjectMapper M = new ObjectMapper();
    static final Map<String, BigDecimal[]> BOOKS = Map.of(
            "XRP/USD", new BigDecimal[]{new BigDecimal("2.4000"), new BigDecimal("2.4100")},
            "USDC/USD", new BigDecimal[]{new BigDecimal("0.9998"), new BigDecimal("1.0002")},
            "RLUSD/USD", new BigDecimal[]{new BigDecimal("0.9997"), new BigDecimal("1.0003")},
            "EMPTY/SYM", new BigDecimal[]{null, null});

    private final ServerSocket server;
    private final String apiKey;
    private final String secret;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<String, String> resting = new ConcurrentHashMap<>(); // clOrderId -> orderId
    private final Map<String, String> restingByOrderId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "mock-ws-md");
        t.setDaemon(true);
        return t;
    });
    public final AtomicInteger connections = new AtomicInteger();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    public final AtomicInteger marketLogons = new AtomicInteger();
    public final AtomicInteger tradeLogons = new AtomicInteger();
    public final AtomicInteger pings = new AtomicInteger();
    /** Every order message received on the trade path, in order (for assertions). */
    public final List<JsonNode> orders = new CopyOnWriteArrayList<>();
    /** Every subscribe message received on the market path. */
    public final List<JsonNode> subscribes = new CopyOnWriteArrayList<>();
    /** Order of successful logons and subscribes: "market", "trade", "subscribe:<symbol>". */
    public final List<String> events = new CopyOnWriteArrayList<>();

    public MockWsServer(int port, String apiKey, String secret) throws IOException {
        this.server = new ServerSocket(port);
        this.apiKey = apiKey;
        this.secret = secret;
        Thread t = new Thread(this::acceptLoop, "mock-ws-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 19877;
        String key = args.length > 1 ? args[1] : "key123";
        String secret = args.length > 2 ? args[2] : "sekret";
        MockWsServer s = new MockWsServer(port, key, secret);
        System.out.println("mock WS taker API on ws://localhost:" + s.port() + "/market and /trade  (apiKey=" + key + ")");
        Thread.currentThread().join();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket c = server.accept();
                connections.incrementAndGet();
                sockets.add(c);
                Thread t = new Thread(() -> serve(c), "mock-ws-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    /** Per-connection state. */
    private final class Peer {
        final OutputStream out;
        final boolean market;
        volatile boolean loggedOn;
        volatile boolean open = true;
        final Map<String, String> subs = new ConcurrentHashMap<>(); // subscriptionId -> instrument

        Peer(OutputStream out, boolean market) {
            this.out = out;
            this.market = market;
        }

        void send(String s) throws IOException {
            synchronized (out) {
                frame(out, 0x81, s.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void serve(Socket c) {
        Peer peer = null;
        try (c) {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            String key = null;
            String request = readLine(in);
            if (request == null) return;
            boolean market = request.contains("/market");
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).trim();
            }
            if (key == null) return;
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            peer = new Peer(out, market);
            if (market) startSnapshots(peer);

            DataInputStream d = new DataInputStream(in);
            while (true) {
                int b0 = d.readUnsignedByte();
                int b1 = d.readUnsignedByte();
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = d.readUnsignedShort();
                else if (len == 127) len = d.readLong();
                byte[] mask = new byte[4];
                if ((b1 & 0x80) != 0) d.readFully(mask);
                byte[] p = new byte[(int) len];
                d.readFully(p);
                for (int i = 0; i < p.length; i++) p[i] ^= mask[i % 4];
                if (op == 0x8) {               // close -> echo close
                    synchronized (out) {
                        frame(out, 0x88, new byte[0]);
                    }
                    return;
                }
                if (op == 0x9) {               // protocol ping -> pong
                    synchronized (out) {
                        frame(out, 0x8A, p);
                    }
                    continue;
                }
                if (op != 0x1) continue;
                if (!handle(peer, new String(p, StandardCharsets.UTF_8))) return;
            }
        } catch (Exception e) {
            // client went away
        } finally {
            if (peer != null) peer.open = false;
        }
    }

    private void startSnapshots(Peer peer) {
        ticker.scheduleAtFixedRate(() -> {
            if (!peer.open) throw new RuntimeException("closed"); // cancels this task
            for (Map.Entry<String, String> s : peer.subs.entrySet()) {
                BigDecimal[] b = BOOKS.get(s.getValue());
                String bids = b[0] == null ? "[]" : "[[" + b[0].toPlainString() + ",1000000],["
                        + b[0].subtract(new BigDecimal("0.0010")).toPlainString() + ",5000000]]";
                String asks = b[1] == null ? "[]" : "[[" + b[1].toPlainString() + ",1000000],["
                        + b[1].add(new BigDecimal("0.0010")).toPlainString() + ",5000000]]";
                try {
                    peer.send("{\"op\":\"snapshot\",\"arg\":{\"instrument\":\"" + s.getValue() + "\",\"subscriptionId\":\""
                            + s.getKey() + "\"},\"data\":[{\"bids\":" + bids + ",\"asks\":" + asks + "}]}");
                } catch (IOException e) {
                    peer.open = false;
                }
            }
        }, 50, 200, TimeUnit.MILLISECONDS);
    }

    /** Returns false to drop the connection. */
    private boolean handle(Peer peer, String text) throws Exception {
        JsonNode j = M.readTree(text);
        String op = j.path("op").asText();
        JsonNode arg = j.path("arg");
        if (op.equals("ping")) {
            pings.incrementAndGet();
            peer.send("{\"op\":\"ping\"}");
            return true;
        }
        if (op.equals("logon")) {
            String expect = WsSession.sign(j.path("ts").asText() + "/verify", secret);
            peer.loggedOn = apiKey.equals(arg.path("apiKey").asText()) && expect.equals(arg.path("sign").asText())
                    && !arg.path("server").asText().isEmpty() && !arg.path("client").asText().isEmpty();
            if (peer.loggedOn) {
                (peer.market ? marketLogons : tradeLogons).incrementAndGet();
                events.add(peer.market ? "market" : "trade");
            }
            peer.send(peer.loggedOn ? "{\"op\":\"logon\",\"arg\":{\"code\":0},\"ts\":1}"
                    : "{\"op\":\"logon\",\"arg\":{\"code\":50103,\"errMsg\":\"Invalid credentials\"},\"ts\":1}");
            return true;
        }
        if (!peer.loggedOn) {
            peer.send("{\"op\":\"invalid\",\"arg\":{\"code\":50100,\"errMsg\":\"Invalid authorization\"}}");
            return true;
        }
        return peer.market ? handleMarket(peer, op, j, arg) : handleTrade(peer, op, arg);
    }

    private boolean handleMarket(Peer peer, String op, JsonNode j, JsonNode arg) throws IOException {
        if (op.equals("subscribe")) {
            subscribes.add(j);
            String sym = arg.path("instrument").asText();
            String subId = arg.path("subscriptionId").asText();
            events.add("subscribe:" + sym);
            ObjectNode ack = arg.deepCopy();
            if (!BOOKS.containsKey(sym)) {
                ack.put("code", 50202);
                ack.put("errMsg", "Unknown symbol");
            } else {
                ack.put("code", 0);
                peer.subs.put(subId, sym);
            }
            peer.send("{\"op\":\"subscribe\",\"arg\":" + ack + "}");
        } else if (op.equals("unsubscribe")) {
            peer.subs.remove(arg.path("subscriptionId").asText());
        } else {
            peer.send("{\"op\":\"invalid\",\"arg\":{\"code\":50400,\"errMsg\":\"Invalid request\"}}");
        }
        return true;
    }

    private boolean handleTrade(Peer peer, String op, JsonNode arg) throws Exception {
        if (op.equals("cancel_order")) {
            String cl = arg.hasNonNull("clOrderId") ? arg.get("clOrderId").asText() : null;
            String oid = arg.hasNonNull("orderId") ? arg.get("orderId").asText() : null;
            if (cl == null && oid != null) cl = restingByOrderId.get(oid);
            String removed = cl == null ? null : resting.remove(cl);
            if (removed != null) {
                restingByOrderId.remove(removed);
                peer.send("{\"op\":\"trade\",\"arg\":{\"clOrderId\":\"" + cl + "\",\"orderId\":\"" + removed
                        + "\",\"state\":\"CANCELED\",\"cumQty\":0}}");
            } else {
                peer.send("{\"op\":\"cancel_order\",\"arg\":{\"clOrderId\":" + M.writeValueAsString(cl)
                        + ",\"code\":\"50400\",\"errMsg\":\"INVALID_CANCEL_ORDER\"}}");
            }
            return true;
        }
        if (!op.equals("order")) {
            peer.send("{\"op\":\"invalid\",\"arg\":{\"code\":50400,\"errMsg\":\"Invalid request\"}}");
            return true;
        }
        orders.add(arg);
        String cl = arg.path("clOrderId").asText();
        String sym = arg.path("instrument").asText();
        String tif = arg.path("tif").asText();
        String side = arg.path("side").asText();
        String type = arg.path("orderType").asText();
        BigDecimal qty = arg.path("quantity").decimalValue();
        BigDecimal px = arg.hasNonNull("price") ? arg.path("price").decimalValue() : null;
        boolean preExec = arg.path("preExec").asBoolean(false);

        if (sym.equals("DROP/SYM")) return false;
        if (sym.equals("SILENT/SYM")) return true;
        if (sym.equals("SLOW/SYM")) {
            Thread.sleep(600);
            peer.send("{\"op\":\"invalid\",\"arg\":{\"code\":50400,\"errMsg\":\"Invalid request\"}}");
            return true;
        }
        if (sym.equals("DELAY/SYM")) {           // slow but successful fill (a long order flight)
            Thread.sleep(600);
            peer.send("{\"op\":\"trade\",\"arg\":{\"clOrderId\":\"" + cl + "\",\"state\":\"FILLED\",\"cumQty\":"
                    + qty.toPlainString() + ",\"lastPrice\":1}}");
            return true;
        }
        if (sym.equals("ERRCL/SYM")) {
            peer.send("{\"op\":\"reject\",\"arg\":{\"clOrderId\":\"" + cl + "\",\"errMsg\":\"INVALID_TIF\"}}");
            return true;
        }
        if (sym.equals("BAD/SYM") || cl.length() > 70 || qty.signum() <= 0) {
            peer.send("{\"op\":\"invalid\",\"arg\":{\"code\":50400,\"errMsg\":\"Invalid request\"},\"ts\":1}");
            return true;
        }
        if (!List.of("IOC", "FOK", "GOOD_TILL_CANCEL").contains(tif)) {
            peer.send("{\"op\":\"order\",\"arg\":{\"clOrderId\":\"" + cl + "\",\"code\":50304,\"errMsg\":\"Invalid time in force\"}}");
            return true;
        }
        BigDecimal[] book = BOOKS.get(sym);
        if (book == null) {
            peer.send("{\"op\":\"order\",\"arg\":{\"clOrderId\":\"" + cl + "\",\"code\":50202,\"errMsg\":\"Unknown symbol\"}}");
            return true;
        }
        String orderId = "A34" + ids.incrementAndGet();
        ObjectNode echo = arg.deepCopy();
        echo.put("orderId", orderId);
        echo.put("code", 0);
        peer.send("{\"op\":\"order\",\"arg\":" + echo + "}");
        String base = "{\"op\":\"trade\",\"arg\":{\"instrument\":\"" + sym + "\",\"side\":\"" + side
                + "\",\"clOrderId\":\"" + cl + "\",\"orderId\":\"" + orderId + "\",\"orderType\":\"" + type + "\",";

        BigDecimal touch = side.equals("BUY") ? book[1] : book[0];
        if (preExec) {
            BigDecimal at = px != null ? px : touch;
            if (at == null) {
                peer.send(base + "\"state\":\"REJECTED\",\"code\":50306,\"errMsg\":\"Invalid price\"}}");
                return true;
            }
            peer.send(base + "\"state\":\"FILLED\",\"quantity\":" + qty.toPlainString() + ",\"cumQty\":" + qty.toPlainString()
                    + ",\"lastQty\":" + qty.toPlainString() + ",\"lastPrice\":" + at.toPlainString()
                    + ",\"avgPrice\":" + at.toPlainString() + ",\"leaveQty\":0}}");
            return true;
        }
        boolean crosses = touch != null && (type.equals("MARKET") || px == null
                || (side.equals("BUY") ? px.compareTo(touch) >= 0 : px.compareTo(touch) <= 0));
        if (!crosses) {
            if (tif.equals("GOOD_TILL_CANCEL")) {
                resting.put(cl, orderId);
                restingByOrderId.put(orderId, cl);
                return true; // the echo above was the ACK
            }
            peer.send(base + "\"state\":\"CANCELED\",\"cumQty\":0,\"leaveQty\":0}}");
            return true;
        }
        boolean thin = sym.equals("USDC/USD") && qty.compareTo(new BigDecimal(500)) > 0;
        if (thin && tif.equals("FOK")) {
            peer.send(base + "\"state\":\"CANCELED\",\"cumQty\":0}}");
            return true;
        }
        if (thin) {
            peer.send(base + "\"state\":\"PARTIALLY_FILLED\",\"cumQty\":500,\"lastQty\":500,\"lastPrice\":"
                    + touch.toPlainString() + "}}");
            peer.send(base + "\"state\":\"CANCELED\",\"cumQty\":500}}");
            return true;
        }
        // field-table spelling (cumQuantity / lastQuantity) on purpose: the client must accept both
        peer.send(base + "\"state\":\"FILLED\",\"quantity\":" + qty.toPlainString() + ",\"cumQuantity\":" + qty.toPlainString()
                + ",\"lastQuantity\":" + qty.toPlainString() + ",\"lastPrice\":" + touch.toPlainString() + ",\"leaveQty\":0}}");
        return true;
    }

    /** Reads one CRLF-terminated header line byte by byte, so no frame bytes are consumed. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char) b);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void frame(OutputStream out, int b0, byte[] p) throws IOException {
        out.write(b0);
        if (p.length < 126) {
            out.write(p.length);
        } else if (p.length < 65536) {
            out.write(126);
            out.write(p.length >> 8);
            out.write(p.length & 0xff);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) out.write((int) ((long) p.length >> (8 * i)) & 0xff);
        }
        out.write(p);
        out.flush();
    }

    /** Simulate a server restart / NLB blip: drop every open connection (market and trade). */
    public void dropAll() {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        sockets.clear();
    }

    @Override
    public void close() throws IOException {
        ticker.shutdownNow();
        server.close();
    }
}
