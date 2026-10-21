package com.cypator.ordertester.md;

import com.cypator.ordertester.config.TesterConfig;
import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Works out the price to send for one order at send time.
 *
 * <ul>
 *   <li><b>Pre-exec</b> (any type): instruments[].preExecPrice[side] if set, otherwise the live touch +/- run.preExecBps.
 *       Pre-exec orders execute at the price sent, so they NEVER use limitPrice, and a price further than
 *       run.preExecMaxDeviationBps from the live touch is refused (the trade-persistor 5% price band).</li>
 *   <li><b>MARKET</b>: if run.sendPriceOnMarket (the API marks price as required) the touch, else limitPrice, else none.</li>
 *   <li><b>LIMIT, priceMode "config"</b>: instruments[].limitPrice[side].</li>
 *   <li><b>LIMIT, priceMode "book"</b>: IOC/FOK cross the touch by run.aggressiveBps (BUY above the ask, SELL below the
 *       bid); GTC/GTD rest run.passiveBps away on the passive side (BUY below the bid, SELL above the ask).</li>
 * </ul>
 * Rounding is always towards the safe side: aggressive prices round away from the touch, passive ones too.
 */
public final class PriceResolver {
    private final TesterConfig cfg;

    public PriceResolver(TesterConfig cfg) {
        this.cfg = cfg;
    }

    public static boolean isBookMode(TesterConfig cfg, TesterConfig.Instrument ins) {
        String mode = ins.priceMode != null ? ins.priceMode : cfg.run.priceMode;
        return "book".equalsIgnoreCase(mode);
    }

    /** Returns the order with its final price (and the book it was priced from). Throws if it cannot be priced. */
    public OrderRequest resolve(OrderRequest o, BookView book) {
        boolean buy = "BUY".equals(o.side);
        int decimals = o.instrument.priceDecimals != null ? o.instrument.priceDecimals : book == null ? 8 : book.decimals;

        if (o.preExec) {
            if (o.price != null) {
                checkPreExecDeviation(o, o.price, book);
                return o.withPricing(o.price, "config", book);
            }
            BigDecimal px = shift(touchOrThrow(o, book), buy ? cfg.run.preExecBps : -cfg.run.preExecBps, decimals, buy);
            return o.withPricing(px, "book-touch", book);
        }

        if (o.type.kind == OrderType.Kind.MARKET) {
            if (!cfg.run.sendPriceOnMarket) return o.withPricing(null, "none", book);
            BigDecimal touch = book == null ? null : book.touch(o.side);
            if (touch != null) return o.withPricing(touch, "book-touch", book);
            BigDecimal lp = o.instrument.limitPrice.get(o.side);
            return lp != null ? o.withPricing(lp, "config", book) : o.withPricing(null, "none", book);
        }

        if (!isBookMode(cfg, o.instrument)) {
            BigDecimal px = o.price != null ? o.price : o.instrument.limitPrice.get(o.side);
            if (px == null) throw new IllegalStateException("no limitPrice." + o.side + " for " + o.instrument.symbol);
            return o.withPricing(px, "config", book);
        }

        if (o.type.isResting()) {
            // passive side: BUY rests below the bid, SELL above the ask
            BigDecimal ref = book == null ? null : buy ? book.bid : book.ask;
            if (ref == null) ref = touchOrThrow(o, book); // one-sided book: fall back to the other side
            BigDecimal px = shift(ref, buy ? -cfg.run.passiveBps : cfg.run.passiveBps, decimals, !buy);
            return o.withPricing(px, "book-passive", book);
        }
        BigDecimal px = shift(touchOrThrow(o, book), buy ? cfg.run.aggressiveBps : -cfg.run.aggressiveBps, decimals, buy);
        return o.withPricing(px, "book-aggressive", book);
    }

    /** A fixed pre-exec price far from market would execute there and trip the price-band check - refuse it. */
    private void checkPreExecDeviation(OrderRequest o, BigDecimal px, BookView book) {
        int max = cfg.run.preExecMaxDeviationBps;
        BigDecimal touch = book == null ? null : book.touch(o.side);
        if (max <= 0 || touch == null || touch.signum() <= 0) return;
        BigDecimal devBps = px.subtract(touch).abs().multiply(BigDecimal.valueOf(10_000)).divide(touch, MathContext.DECIMAL64);
        if (devBps.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalStateException("pre-exec price " + px.toPlainString() + " is "
                    + devBps.setScale(0, RoundingMode.HALF_UP).toPlainString() + " bps from the touch "
                    + touch.stripTrailingZeros().toPlainString() + " (max run.preExecMaxDeviationBps=" + max
                    + "): pre-exec executes at the price sent and would trip the price-band check");
        }
    }

    private static BigDecimal touchOrThrow(OrderRequest o, BookView book) {
        BigDecimal t = book == null ? null : book.touch(o.side);
        if (t == null) {
            throw new IllegalStateException("cannot price " + o.label() + " " + o.side + " from the book: no "
                    + ("BUY".equals(o.side) ? "ask" : "bid") + " for " + o.instrument.symbol);
        }
        return t;
    }

    /** price * (1 + bps/10000), rounded to decimals; roundUp=true rounds towards +inf. */
    static BigDecimal shift(BigDecimal price, int bps, int decimals, boolean roundUp) {
        BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(bps).movePointLeft(4));
        BigDecimal px = price.multiply(factor).setScale(Math.max(0, decimals), roundUp ? RoundingMode.CEILING : RoundingMode.FLOOR);
        if (px.signum() <= 0) throw new IllegalStateException("computed a non-positive price from " + price);
        return px;
    }
}
