package com.cypator.ordertester.md;

import com.cypator.ordertester.config.TesterConfig.Instrument;
import com.cypator.ordertester.config.TesterConfig.MarketConfig;
import com.cypator.ordertester.config.TesterConfig.WsConfig;
import com.cypator.ordertester.util.Templates;
import com.cypator.ordertester.ws.WsSession;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Market session: logs on, subscribes a snapshot book per instrument and keeps the latest top of book.
 * The trade session is separate ({@code TradeClient}); per the API docs both must be logged on before any request,
 * so the caller (Main) drives the order: market logon, trade logon, then {@link #subscribe} / {@link #recover}.
 */
public final class MarketDataClient implements AutoCloseable, WsSession.Handler {
    private final WsConfig ws;
    private final MarketConfig mc;
    private final WsSession session;
    /** instrument symbol -> book, in subscription order. */
    private final Map<String, Book> books = new LinkedHashMap<>();
    private final Map<String, Book> bySubscriptionId = new ConcurrentHashMap<>();
    private final Map<String, Instrument> instruments = new LinkedHashMap<>();
    private int subSeq;
    /** Last market-data error that named no subscription/instrument (shown, but not applied to any book). */
    private volatile String uncorrelatedError;

    public MarketDataClient(WsConfig ws) {
        this.ws = ws;
        this.mc = ws.market;
        if (mc.subscribeTemplate == null || mc.subscribeTemplate.isNull()) {
            throw new IllegalArgumentException("Missing config: ws.market.subscribeTemplate");
        }
        this.session = new WsSession("market", ws, mc, this);
    }

    public void validate() {
        session.validate();
    }

    public void connect() throws Exception {
        session.connect();
    }

    /** Reconnect the market socket if it is gone (no subscribe yet - see {@link #recover}). True if it reconnected. */
    public boolean ensureConnected() throws Exception {
        return session.ensureConnected();
    }

    /** Subscribe every instrument once (in order). Call only after BOTH sessions are logged on. */
    public synchronized void subscribe(Collection<Instrument> list) throws Exception {
        for (Instrument i : list) {
            if (books.containsKey(i.symbol)) continue;
            instruments.put(i.symbol, i);
            Book b = new Book(i.symbol, nextSubscriptionId());
            books.put(i.symbol, b);
            sendSubscribe(i, b);
        }
    }

    /**
     * After a reconnect (call once BOTH sessions are logged on again):
     * <ul>
     *   <li>new market socket: re-subscribe every instrument under a new id and wait for each to answer;
     *       until then the old snapshot is NOT usable for pricing;</li>
     *   <li>new trade socket only: the subscriptions are still alive (market data pauses while the trade session
     *       is down and resumes on its own) - wait up to waitMs for fresh snapshots; a quiet instrument keeps its book.</li>
     * </ul>
     */
    public void recover(boolean marketReconnected, boolean tradeReconnected, long waitMs) throws Exception {
        if (!marketReconnected && !tradeReconnected) return;
        List<Book> list;
        synchronized (this) {
            list = new ArrayList<>(books.values());
            if (marketReconnected) {
                bySubscriptionId.clear();
                uncorrelatedError = null;
                for (Map.Entry<String, Book> e : books.entrySet()) {
                    e.getValue().rearm(nextSubscriptionId());
                    sendSubscribe(instruments.get(e.getKey()), e.getValue());
                }
            } else {
                for (Book b : list) b.rearm(null);
            }
        }
        if (list.isEmpty()) return;
        System.out.println("[market] " + (marketReconnected ? "re-subscribed after reconnect" : "trade session reconnected")
                + " - waiting up to " + waitMs + " ms for fresh snapshots");
        awaitAll(list, waitMs);
        if (!marketReconnected) {
            for (Book b : list) b.keepLastSnapshot();
        }
    }

    /** Wait until every subscribed instrument answered (snapshot or subscribe error). */
    public boolean awaitBooks(long timeoutMs) throws InterruptedException {
        return awaitAll(snapshotOfBooks(), timeoutMs);
    }

    private static boolean awaitAll(List<Book> list, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean all = true;
        for (Book b : list) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0 || !b.awaitAnswer(left)) all = false;
        }
        return all;
    }

    /** Current view of an instrument; null if it was never subscribed. */
    public BookView view(String symbol) {
        Book b;
        synchronized (this) {
            b = books.get(symbol);
        }
        return b == null ? null : b.view();
    }

    public List<BookView> views() {
        List<BookView> out = new ArrayList<>();
        for (Book b : snapshotOfBooks()) out.add(b.view());
        return out;
    }

    public String uncorrelatedError() {
        return uncorrelatedError;
    }

    /** Why this book cannot be used for trading, or null if it is usable. */
    public String unusableReason(BookView v) {
        if (v == null) return "instrument was not subscribed";
        if (v.error != null) return "market data subscribe failed: " + v.error;
        if (!session.isOpen()) return "market data session is down";
        if (!v.hasSnapshot()) {
            String hint = uncorrelatedError;
            return "no market data snapshot received" + (hint == null ? "" : " (server said: " + hint + ")");
        }
        if (!v.fresh) return "no snapshot on the new subscription yet (after reconnect) - old book not used";
        if (mc.maxBookAgeMs > 0 && v.ageMs > mc.maxBookAgeMs) {
            return "market data is stale: last snapshot " + v.ageMs + " ms ago (maxBookAgeMs " + mc.maxBookAgeMs + ")";
        }
        return null;
    }

    private synchronized List<Book> snapshotOfBooks() {
        return new ArrayList<>(books.values());
    }

    private String nextSubscriptionId() {
        return mc.subscriptionIdPrefix + "-" + (++subSeq) + "-" + System.currentTimeMillis() % 100000;
    }

    private void sendSubscribe(Instrument i, Book b) throws Exception {
        bySubscriptionId.put(b.subscriptionId, b);
        session.send(Templates.renderToString(mc.subscribeTemplate, vars(i, b.subscriptionId), ws.dropNullFields), true);
    }

    private Map<String, Object> vars(Instrument i, String subId) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("instrument", i.symbol);
        v.put("instrumentId", i.instrumentId);
        v.put("subscriptionId", subId);
        v.put("nowMs", System.currentTimeMillis());
        return v;
    }

    // ---------------------------------------------------------------- WsSession.Handler

    @Override
    public void onMessage(JsonNode msg, String text) {
        String op = session.op(msg);
        String subId = Templates.at(msg, mc.subscriptionIdPointer);
        String symbol = Templates.at(msg, mc.instrumentPointer);
        Book b = subId == null ? null : bySubscriptionId.get(subId);
        if (b == null && subId == null && symbol != null) {
            synchronized (this) {
                b = books.get(symbol);
            }
        }
        boolean ok = session.codeOk(msg);
        boolean snapshot = WsSession.containsIgnoreCase(mc.snapshotOps, op);
        boolean subscribeReply = WsSession.containsIgnoreCase(mc.subscribeOps, op);
        if (snapshot && ok) {
            if (b != null) b.onSnapshot(msg.at(mc.bidsPointer), msg.at(mc.asksPointer));
            return; // a snapshot for an old (replaced) subscription id is ignored
        }
        if (ok) return; // subscribe ack, unsubscribe ack ...
        String err = session.errorText(msg);
        if (b != null && (snapshot || subscribeReply)) {
            b.onError(err);
        } else if (b == null && subId == null && symbol == null) {
            // e.g. {"op":"invalid","arg":{"code":50400}} without echo: it cannot be tied to one book. Record it
            // (it is shown when a book times out) instead of failing books that may still answer normally.
            uncorrelatedError = err;
            System.out.println("[market] uncorrelated error (not applied to any book): " + err);
        }
    }

    @Override
    public void onLost(String why) {
        // Books keep their last snapshot; unusableReason() reports the session as down until recover().
    }

    @Override
    public synchronized void close() {
        if (session.isOpen() && mc.unsubscribeTemplate != null && !mc.unsubscribeTemplate.isNull()) {
            for (Map.Entry<String, Book> e : books.entrySet()) {
                try {
                    session.send(Templates.renderToString(mc.unsubscribeTemplate,
                            vars(instruments.get(e.getKey()), e.getValue().subscriptionId), ws.dropNullFields), true);
                } catch (Exception ex) {
                    System.out.println("[market] unsubscribe failed: " + ex.getMessage());
                }
            }
        }
        session.close();
    }
}
