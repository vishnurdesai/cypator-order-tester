package com.cypator.ordertester.md;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Latest snapshot of one instrument, updated by the market-session socket thread. */
final class Book {
    final String instrument;
    /** Current subscription id (a re-subscribe uses a new id). */
    volatile String subscriptionId;
    private BigDecimal bid, bidQty, ask, askQty;
    private int bidLevels, askLevels;
    /** Max price scale seen on any level of any snapshot (sticky: a round touch must not lose decimals). */
    private int decimals;
    private long updates;
    private long lastNanos;
    private String error;
    /** A snapshot (or error) arrived for the current subscription since the last {@link #rearm}. */
    private boolean fresh;
    private CountDownLatch answer = new CountDownLatch(1);

    Book(String instrument, String subscriptionId) {
        this.instrument = instrument;
        this.subscriptionId = subscriptionId;
    }

    /** bids/asks are arrays of [price, qty]; best price = highest bid / lowest ask regardless of ordering. */
    synchronized void onSnapshot(JsonNode bids, JsonNode asks) {
        BigDecimal[] b = best(bids, true);
        BigDecimal[] a = best(asks, false);
        bid = b[0];
        bidQty = b[1];
        ask = a[0];
        askQty = a[1];
        bidLevels = levels(bids);
        askLevels = levels(asks);
        decimals = Math.max(decimals, Math.max(maxScale(bids), maxScale(asks)));
        updates++;
        lastNanos = System.nanoTime();
        error = null;
        fresh = true;
        answer.countDown();
    }

    synchronized void onError(String err) {
        error = err;
        fresh = true;
        answer.countDown();
    }

    /**
     * Wait for a new answer: after a re-subscribe (new id) the old snapshot must not be used until the new
     * subscription delivers; keepData=true keeps the last prices for display.
     */
    synchronized void rearm(String newSubscriptionId) {
        if (newSubscriptionId != null) subscriptionId = newSubscriptionId;
        error = null;
        fresh = false;
        answer = new CountDownLatch(1);
    }

    /** The subscription is still alive but quiet: keep using the last snapshot (only after a trade-only reconnect). */
    synchronized void keepLastSnapshot() {
        if (!fresh && updates > 0) fresh = true;
    }

    boolean awaitAnswer(long timeoutMs) throws InterruptedException {
        CountDownLatch l;
        synchronized (this) {
            l = answer;
        }
        return l.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    synchronized BookView view() {
        long age = updates == 0 ? -1 : (System.nanoTime() - lastNanos) / 1_000_000;
        return new BookView(instrument, bid, bidQty, ask, askQty, bidLevels, askLevels, updates, age, decimals, error, fresh);
    }

    private static int levels(JsonNode side) {
        return side != null && side.isArray() ? side.size() : 0;
    }

    private static BigDecimal[] best(JsonNode side, boolean highest) {
        BigDecimal px = null, qty = null;
        if (side != null && side.isArray()) {
            for (JsonNode lvl : side) {
                if (!lvl.isArray() || lvl.size() < 1 || !lvl.get(0).isNumber()) continue;
                BigDecimal p = lvl.get(0).decimalValue();
                BigDecimal q = lvl.size() > 1 && lvl.get(1).isNumber() ? lvl.get(1).decimalValue() : null;
                if (q != null && q.signum() <= 0) continue;
                if (px == null || (highest ? p.compareTo(px) > 0 : p.compareTo(px) < 0)) {
                    px = p;
                    qty = q;
                }
            }
        }
        return new BigDecimal[]{px, qty};
    }

    private static int maxScale(JsonNode side) {
        int max = 0;
        if (side != null && side.isArray()) {
            for (JsonNode lvl : side) {
                if (lvl.isArray() && lvl.size() > 0 && lvl.get(0).isNumber()) {
                    max = Math.max(max, Math.max(0, lvl.get(0).decimalValue().scale()));
                }
            }
        }
        return max;
    }
}
