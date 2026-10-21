package com.cypator.ordertester.model;

import com.cypator.ordertester.config.TesterConfig.Instrument;
import com.cypator.ordertester.md.BookView;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One order to send, produced by the ScenarioGenerator. Book-priced orders are generated without
 * a price and re-created with {@link #withPricing} right before they are sent.
 */
public final class OrderRequest {
    public final int seq;
    public final Instrument instrument;
    public final OrderType type;
    /** Pre-execution order ("preExec": true on the wire). */
    public final boolean preExec;
    public final String side;          // BUY / SELL
    public final BigDecimal quantity;
    /** Final price sent; null for MARKET (unless sendPriceOnMarket) and for book-priced orders not yet resolved. */
    public final BigDecimal price;
    /** config / book-aggressive / book-passive / book-touch / none / pending-book. */
    public final String priceSource;
    /** The book at the time the price was resolved (null without market data). */
    public final BookView book;
    public final String currency;      // currency the quantity is expressed in (base or term)
    public final boolean termQuantity;
    public final String clOrdId;
    /** GTD lifetime in minutes (expiry is computed when the order is rendered); null for other TIFs. */
    public final Integer gtdExpireMinutes;
    /** orderVars + instrument vars, merged by the generator. */
    public final Map<String, Object> extraVars;

    public OrderRequest(int seq, Instrument instrument, OrderType type, boolean preExec, String side, BigDecimal quantity,
                        BigDecimal price, String priceSource, BookView book, String currency, boolean termQuantity,
                        String clOrdId, Integer gtdExpireMinutes, Map<String, Object> extraVars) {
        this.seq = seq;
        this.instrument = instrument;
        this.type = type;
        this.preExec = preExec;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.priceSource = priceSource;
        this.book = book;
        this.currency = currency;
        this.termQuantity = termQuantity;
        this.clOrdId = clOrdId;
        this.gtdExpireMinutes = gtdExpireMinutes;
        this.extraVars = extraVars == null ? Map.of() : extraVars;
    }

    /** Short constructor for tests and simple callers: config-priced, no book, no extra vars. */
    public OrderRequest(int seq, Instrument instrument, OrderType type, String side, BigDecimal quantity,
                        BigDecimal price, String currency, boolean termQuantity, String clOrdId, Integer gtdExpireMinutes) {
        this(seq, instrument, type, false, side, quantity, price, price == null ? "none" : "config", null,
                currency, termQuantity, clOrdId, gtdExpireMinutes, null);
    }

    public OrderRequest withPricing(BigDecimal newPrice, String source, BookView atSend) {
        return new OrderRequest(seq, instrument, type, preExec, side, quantity, newPrice, source, atSend, currency,
                termQuantity, clOrdId, gtdExpireMinutes, extraVars);
    }

    /** Scenario label used in reports and as the expect key: LIMIT_FOK or LIMIT_FOK+PREEXEC. */
    public String label() {
        return preExec ? type.name() + "+PREEXEC" : type.name();
    }

    /** Variables available to the order / cancel templates. */
    public Map<String, Object> templateVars(Map<String, String> tifMap) {
        Map<String, Object> v = new LinkedHashMap<>(extraVars);
        v.put("clOrdId", clOrdId);
        v.put("instrument", instrument.symbol);
        v.put("instrumentId", instrument.instrumentId);
        v.put("base", instrument.base());
        v.put("term", instrument.term());
        v.put("side", side);
        v.put("sideLower", side.toLowerCase());
        v.put("quantity", quantity);
        v.put("price", price);
        v.put("currency", currency);
        v.put("orderType", type.orderTypeName());
        v.put("tif", tifMap == null ? type.tif : tifMap.getOrDefault(type.tif, type.tif));
        v.put("type", type.name());
        v.put("preExec", preExec);
        long now = System.currentTimeMillis();
        v.put("expireTimeMs", gtdExpireMinutes == null ? null : now + gtdExpireMinutes * 60_000L);
        v.put("nowMs", now);
        v.put("uuid", java.util.UUID.randomUUID().toString());
        return v;
    }

    public String describe() {
        String px = price != null ? " @ " + price.toPlainString()
                : "pending-book".equals(priceSource) ? " @ <live book>" : "";
        return String.format("#%d %s %s %s %s%s%s", seq, label(), side, quantity.toPlainString(),
                termQuantity ? currency : instrument.symbol, px,
                termQuantity ? " (term qty on " + instrument.symbol + ")" : "");
    }
}
