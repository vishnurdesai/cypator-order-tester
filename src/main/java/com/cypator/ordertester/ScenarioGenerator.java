package com.cypator.ordertester;

import com.cypator.ordertester.config.TesterConfig;
import com.cypator.ordertester.config.TesterConfig.Instrument;
import com.cypator.ordertester.md.PriceResolver;
import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expands the config into the ordered list of orders to send.
 *
 * Nesting, outermost first: repeat, size index, side, quantity currency, instrument, scenario
 * (each order type, then each pre-exec order type). A small maxOrders therefore still covers every
 * scenario on every instrument before it moves on to the second size or the other side.
 */
public final class ScenarioGenerator {
    /** The WS API answers a longer clOrderId with a generic 50400 Invalid request. */
    static final int MAX_CLORDID_BYTES = 70;

    /** Which pre-exec scenarios to include. */
    public enum PreExecFilter { INCLUDE, NONE, ONLY }

    private ScenarioGenerator() {
    }

    private static final class Scenario {
        final OrderType type;
        final boolean preExec;

        Scenario(OrderType type, boolean preExec) {
            this.type = type;
            this.preExec = preExec;
        }
    }

    public static List<OrderRequest> generate(TesterConfig cfg, String runId, int maxOrders,
                                              Set<OrderType> typeFilter, Set<String> instrumentFilter,
                                              Set<String> sideFilter) {
        return generate(cfg, runId, maxOrders, typeFilter, instrumentFilter, sideFilter, PreExecFilter.INCLUDE);
    }

    public static List<OrderRequest> generate(TesterConfig cfg, String runId, int maxOrders,
                                              Set<OrderType> typeFilter, Set<String> instrumentFilter,
                                              Set<String> sideFilter, PreExecFilter preExecFilter) {
        boolean anyTypes = !cfg.orderTypes.isEmpty() || !cfg.preExecOrderTypes.isEmpty()
                || cfg.instruments.stream().anyMatch(i -> i.orderTypes != null || i.preExecOrderTypes != null);
        if (!anyTypes) throw new IllegalArgumentException("No orderTypes / preExecOrderTypes configured");

        List<Instrument> instruments = new ArrayList<>();
        for (Instrument i : cfg.instruments) {
            if (i.symbol == null || i.symbol.isBlank()) throw new IllegalArgumentException("Instrument without symbol");
            String mode = i.priceMode != null ? i.priceMode : cfg.run.priceMode;
            if (!"config".equalsIgnoreCase(mode) && !"book".equalsIgnoreCase(mode)) {
                throw new IllegalArgumentException("priceMode must be \"config\" or \"book\", got '" + mode + "' (" + i.symbol + ")");
            }
            if (instrumentFilter.isEmpty() || instrumentFilter.contains(i.symbol.toUpperCase())) instruments.add(i);
        }
        if (instruments.isEmpty()) throw new IllegalArgumentException("No instruments selected");

        List<String> sides = new ArrayList<>();
        for (String s : cfg.run.sides) {
            String side = s.toUpperCase();
            if (!side.equals("BUY") && !side.equals("SELL")) throw new IllegalArgumentException("Invalid side: " + s);
            if (sideFilter.isEmpty() || sideFilter.contains(side)) sides.add(side);
        }
        if (sides.isEmpty()) throw new IllegalArgumentException("No sides selected");

        int maxSizes = instruments.stream().mapToInt(i -> i.sizes.size()).max().orElse(0);
        if (maxSizes == 0) throw new IllegalArgumentException("No sizes configured for the selected instruments");

        List<OrderRequest> out = new ArrayList<>();
        int seq = 0;
        outer:
        for (int r = 0; r < Math.max(1, cfg.run.repeat); r++) {
            for (int sizeIdx = 0; sizeIdx < maxSizes; sizeIdx++) {
                for (String side : sides) {
                    for (String qtyIn : List.of("BASE", "TERM")) {
                        for (Instrument ins : instruments) {
                            if (sizeIdx >= ins.sizes.size()) continue;
                            if (ins.quantityIn.stream().noneMatch(q -> q.equalsIgnoreCase(qtyIn))) continue;
                            for (Scenario sc : scenarios(cfg, ins, preExecFilter)) {
                                if (!typeFilter.isEmpty() && !typeFilter.contains(sc.type)) continue;
                                if (maxOrders > 0 && out.size() >= maxOrders) break outer;

                                BigDecimal qty = ins.sizes.get(sizeIdx);
                                if (qty == null || qty.signum() <= 0) {
                                    throw new IllegalArgumentException("Instrument " + ins.symbol + " has a non-positive size: " + qty);
                                }
                                boolean bookMode = PriceResolver.isBookMode(cfg, ins);
                                boolean marketData = cfg.ws == null || cfg.ws.market.enabled;
                                BigDecimal price = null;
                                String source = "none";
                                if (sc.preExec) {
                                    // Pre-exec executes at the price sent: never reuse the (often off-market) limitPrice.
                                    price = ins.preExecPrice.get(side);
                                    if (price != null) {
                                        if (price.signum() <= 0) {
                                            throw new IllegalArgumentException("Instrument " + ins.symbol + " has a non-positive preExecPrice." + side);
                                        }
                                        source = "config";
                                    } else if (marketData) {
                                        source = "pending-book";
                                    } else {
                                        throw new IllegalArgumentException("Pre-exec orders on " + ins.symbol + " need preExecPrice."
                                                + side + " or market data (they execute at the price sent)");
                                    }
                                } else if (sc.type.kind == OrderType.Kind.MARKET) {
                                    // price optional: touch at send time, else limitPrice, else omitted
                                    source = cfg.run.sendPriceOnMarket ? "pending-market" : "none";
                                } else if (bookMode) {
                                    source = "pending-book";
                                } else {
                                    price = ins.limitPrice.get(side);
                                    if (price == null) {
                                        throw new IllegalArgumentException("Instrument " + ins.symbol + " has no limitPrice."
                                                + side + " but " + sc.type + " needs one (or use priceMode \"book\")");
                                    }
                                    if (price.signum() <= 0) {
                                        throw new IllegalArgumentException("Instrument " + ins.symbol + " has a non-positive limitPrice." + side);
                                    }
                                    source = "config";
                                }
                                boolean term = qtyIn.equals("TERM");
                                seq++;
                                String clOrdId = String.format("%s-WS-%s-%03d", cfg.run.clOrdIdPrefix, runId, seq);
                                // "-C" is appended for the cancel id of GTC/GTD orders
                                if ((clOrdId + "-C").getBytes(StandardCharsets.US_ASCII).length > MAX_CLORDID_BYTES) {
                                    throw new IllegalArgumentException("clOrdId longer than " + (MAX_CLORDID_BYTES - 2)
                                            + " bytes - shorten run.clOrdIdPrefix: " + clOrdId);
                                }
                                Integer expire = "GTD".equals(sc.type.tif) ? cfg.run.gtdExpireMinutes : null;
                                Map<String, Object> vars = new LinkedHashMap<>(cfg.orderVars);
                                if (ins.vars != null) vars.putAll(ins.vars);
                                out.add(new OrderRequest(seq, ins, sc.type, sc.preExec, side, qty, price, source, null,
                                        term ? ins.term() : ins.base(), term, clOrdId, expire, vars));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<Scenario> scenarios(TesterConfig cfg, Instrument ins, PreExecFilter f) {
        List<Scenario> list = new ArrayList<>();
        if (f != PreExecFilter.ONLY) {
            for (OrderType t : ins.orderTypes != null ? ins.orderTypes : cfg.orderTypes) list.add(new Scenario(t, false));
        }
        if (f != PreExecFilter.NONE) {
            for (OrderType t : ins.preExecOrderTypes != null ? ins.preExecOrderTypes : cfg.preExecOrderTypes) {
                if (t.isResting()) {
                    throw new IllegalArgumentException("preExecOrderTypes cannot contain " + t + " (" + ins.symbol
                            + "): a pre-exec order executes at the price sent and is never left resting / canceled");
                }
                list.add(new Scenario(t, true));
            }
        }
        return list;
    }
}
