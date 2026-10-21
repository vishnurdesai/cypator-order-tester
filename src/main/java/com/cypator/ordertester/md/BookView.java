package com.cypator.ordertester.md;

import java.math.BigDecimal;

/** Immutable top-of-book picture of one instrument at one moment. */
public final class BookView {
    public final String instrument;
    public final BigDecimal bid;      // null = no bids
    public final BigDecimal bidQty;
    public final BigDecimal ask;      // null = no asks
    public final BigDecimal askQty;
    public final int bidLevels;
    public final int askLevels;
    public final long updates;
    /** Age of the last snapshot in ms; -1 = never received. */
    public final long ageMs;
    /** Price decimals used by the book (max scale seen at the touch). */
    public final int decimals;
    /** Subscribe error, if the subscription was refused. */
    public final String error;
    /** The current subscription has answered (false right after a re-subscribe, until its first snapshot). */
    public final boolean fresh;

    public BookView(String instrument, BigDecimal bid, BigDecimal bidQty, BigDecimal ask, BigDecimal askQty,
                    int bidLevels, int askLevels, long updates, long ageMs, int decimals, String error) {
        this(instrument, bid, bidQty, ask, askQty, bidLevels, askLevels, updates, ageMs, decimals, error, updates > 0);
    }

    public BookView(String instrument, BigDecimal bid, BigDecimal bidQty, BigDecimal ask, BigDecimal askQty,
                    int bidLevels, int askLevels, long updates, long ageMs, int decimals, String error, boolean fresh) {
        this.instrument = instrument;
        this.bid = bid;
        this.bidQty = bidQty;
        this.ask = ask;
        this.askQty = askQty;
        this.bidLevels = bidLevels;
        this.askLevels = askLevels;
        this.updates = updates;
        this.ageMs = ageMs;
        this.decimals = decimals;
        this.error = error;
        this.fresh = fresh;
    }

    public boolean hasSnapshot() {
        return updates > 0;
    }

    public BigDecimal touch(String side) {
        return "BUY".equals(side) ? ask : bid;
    }

    public String summary() {
        if (error != null) return instrument + ": SUBSCRIBE FAILED - " + error;
        if (!hasSnapshot()) return instrument + ": no snapshot received";
        return String.format("%s: bid %s x %s | ask %s x %s  (%d/%d levels, %d snapshots, last %d ms ago)",
                instrument, plain(bid), plain(bidQty), plain(ask), plain(askQty), bidLevels, askLevels, updates, ageMs);
    }

    private static String plain(BigDecimal d) {
        return d == null ? "-" : d.stripTrailingZeros().toPlainString();
    }
}
