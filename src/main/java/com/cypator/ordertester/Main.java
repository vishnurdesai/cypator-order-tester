package com.cypator.ordertester;

import com.cypator.ordertester.ScenarioGenerator.PreExecFilter;
import com.cypator.ordertester.config.ConfigLoader;
import com.cypator.ordertester.config.TesterConfig;
import com.cypator.ordertester.config.TesterConfig.Instrument;
import com.cypator.ordertester.md.BookView;
import com.cypator.ordertester.md.MarketDataClient;
import com.cypator.ordertester.md.PriceResolver;
import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderResult;
import com.cypator.ordertester.model.OrderStatus;
import com.cypator.ordertester.model.OrderType;
import com.cypator.ordertester.report.Reporter;
import com.cypator.ordertester.util.Templates;
import com.cypator.ordertester.ws.TradeClient;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Session flow (as the WS Taker API requires both sessions to be logged on):
 * market session connect + logon -> trade session connect + logon -> subscribe every instrument ->
 * wait for the first book -> price and send each order on the trade session -> unsubscribe -> close.
 */
public final class Main {

    public static void main(String[] argv) {
        int rc;
        try {
            Args a = Args.parse(argv);
            if (a.help) {
                System.out.println(Args.USAGE);
                return;
            }
            rc = new Main().run(a);
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err.println("Run with --help for usage.");
            rc = 2;
        } catch (Exception e) {
            System.err.println("ERROR: " + e);
            rc = 2;
        }
        System.exit(rc);
    }

    int run(Args a) throws Exception {
        TesterConfig cfg = ConfigLoader.load(Path.of(a.config));
        if (cfg.ws == null) throw new IllegalArgumentException("Missing 'ws' section in config");
        if (a.marketUrl != null) cfg.ws.market.url = a.marketUrl;
        if (a.tradeUrl != null) cfg.ws.trade.url = a.tradeUrl;
        if (a.noMarketData) cfg.ws.market.enabled = false;
        if (a.repeat != null) cfg.run.repeat = a.repeat;
        if (a.timeoutMs != null) cfg.run.orderTimeoutMs = a.timeoutMs;
        int maxOrders = a.maxOrders != null ? a.maxOrders : cfg.run.maxOrders;
        validateRun(cfg, maxOrders);

        String guard = prodGuard(cfg);
        if (guard != null && !a.allowProd && !a.dryRun) {
            System.err.println(guard + " These are REAL orders - rerun with --allow-prod if you really mean it.");
            return 2;
        }

        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmssSSS"));
        List<OrderRequest> all = ScenarioGenerator.generate(cfg, runId, 0, a.types, a.instruments, a.sides, a.preExec);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("The config and filters select no orders "
                    + "(check --types/--instruments/--sides/--pre-exec, orderTypes and preExecOrderTypes)");
        }
        List<OrderRequest> orders = maxOrders > 0 && all.size() > maxOrders ? new ArrayList<>(all.subList(0, maxOrders)) : all;
        String capNote = capNote(all, orders, maxOrders);
        checkTemplateFields(cfg, orders);
        boolean md = cfg.ws.market.enabled;
        if (!md && orders.stream().anyMatch(o -> "pending-book".equals(o.priceSource))) {
            throw new IllegalArgumentException("priceMode \"book\" and pre-exec orders without preExecPrice need market data,"
                    + " but ws.market.enabled=false / --no-market-data");
        }

        System.out.printf("Order tester run %s  env=%s  orders=%d of %d%s%n", runId, cfg.environment, orders.size(), all.size(),
                a.dryRun ? "  (DRY RUN)" : "");
        if (capNote != null) System.out.println("WARNING: " + capNote);
        System.out.println("  market session: " + (md ? cfg.ws.market.url + " (client " + cfg.ws.market.client + ")" : "DISABLED"));
        System.out.println("  trade session:  " + cfg.ws.trade.url + " (client " + cfg.ws.trade.client + ")");
        if (guard != null) System.out.println("WARNING: " + guard);

        try (MarketDataClient mdc = md ? new MarketDataClient(cfg.ws) : null;
             TradeClient trade = new TradeClient(cfg.ws)) {
            trade.checkTemplates(orders); // unknown ${vars} / unset ${env:} are config errors before any socket
            if (a.dryRun) {
                try {
                    if (mdc != null) mdc.validate();
                    trade.validate();
                } catch (IllegalArgumentException e) {
                    System.out.println("WARNING: a real run would stop here (exit 2): " + e.getMessage());
                }
                if (md) System.out.println("  subscribe: " + String.join(", ", symbols(orders)));
                for (OrderRequest o : orders) {
                    System.out.println("  " + o.describe() + "\n    " + trade.preview(o)
                            + ("pending-book".equals(o.priceSource) ? "   (price filled in from the live book at send time)"
                            : "pending-market".equals(o.priceSource) ? "   (touch price added at send time)" : ""));
                }
                return 0;
            }
            // config problems (unset credentials, bad URLs) are exit 2, not order failures
            if (mdc != null) mdc.validate();
            trade.validate();
            if (a.check) return runCheck(cfg, mdc, trade, orders);

            List<OrderResult> results = runSession(cfg, mdc, trade, orders);
            Reporter.printTable(results);
            if (mdc != null) printBooks("Market data at end of run", mdc);
            Path csv = Reporter.writeCsv(Path.of(cfg.run.reportDir), runId, cfg.environment, results);
            System.out.println("report: " + csv.toAbsolutePath());
            boolean allPassed = results.size() == orders.size() && results.stream().allMatch(r -> r.passed);
            System.out.println(allPassed ? "\nALL PASSED" : "\nSOME ORDERS FAILED");
            return allPassed ? 0 : 1;
        }
    }

    private List<OrderResult> runSession(TesterConfig cfg, MarketDataClient mdc, TradeClient trade, List<OrderRequest> orders) {
        List<OrderResult> results = new ArrayList<>();
        // 1. market session first
        if (mdc != null) {
            try {
                mdc.connect();
            } catch (Exception e) {
                return failAll(orders, "market data session: " + msg(e));
            }
        }
        // 2. then the trade session
        try {
            trade.connect();
        } catch (Exception e) {
            return failAll(orders, "trade session: " + msg(e));
        }
        // 3. subscribe and wait for the first book of every instrument
        if (mdc != null) {
            try {
                mdc.subscribe(instruments(orders));
                boolean all = mdc.awaitBooks(cfg.ws.market.waitForBookMs);
                printBooks(all ? "Market data ready" : "Market data NOT complete after " + cfg.ws.market.waitForBookMs + " ms", mdc);
            } catch (Exception e) {
                return failAll(orders, "market data subscribe: " + msg(e));
            }
        }
        // 4. trade
        PriceResolver resolver = new PriceResolver(cfg);
        for (int i = 0; i < orders.size(); i++) {
            OrderRequest o = orders.get(i);
            OrderResult r = sendOne(cfg, mdc, trade, resolver, o);
            r.passed = passStatuses(cfg, r.request).contains(r.status);
            System.out.printf("[trade]   -> %s %s filled=%s%s (%s ms) %s%n", r.status, r.passed ? "PASS" : "FAIL",
                    r.filledQty.toPlainString(), r.avgPrice == null ? "" : " @ " + r.avgPrice.toPlainString(),
                    r.totalLatencyMs < 0 ? "-" : String.valueOf(r.totalLatencyMs), r.text);
            results.add(r);
            if (!r.passed && cfg.run.stopOnFailure) {
                System.out.println("stopOnFailure=true - skipping the remaining " + (orders.size() - i - 1) + " orders");
                break;
            }
            if (i < orders.size() - 1 && cfg.run.delayBetweenOrdersMs > 0) {
                try {
                    Thread.sleep(cfg.run.delayBetweenOrdersMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return results;
    }

    /**
     * --check: market logon, trade logon, subscribe, show the books - and send NO orders.
     * Exit 0 when both sessions logged on and every instrument has a usable book, else 1.
     */
    private int runCheck(TesterConfig cfg, MarketDataClient mdc, TradeClient trade, List<OrderRequest> orders) throws Exception {
        System.out.println("CHECK MODE: connecting and subscribing only - no orders will be sent");
        if (mdc != null) {
            try {
                mdc.connect();
            } catch (Exception e) {
                System.out.println("CHECK FAILED: market data session: " + msg(e));
                return 1;
            }
        }
        try {
            trade.connect();
        } catch (Exception e) {
            System.out.println("CHECK FAILED: trade session: " + msg(e));
            return 1;
        }
        if (mdc == null) {
            System.out.println("CHECK OK: trade session logged on (market data disabled)");
            return 0;
        }
        mdc.subscribe(instruments(orders));
        mdc.awaitBooks(cfg.ws.market.waitForBookMs);
        printBooks("Market data", mdc);
        int bad = 0;
        for (BookView v : mdc.views()) {
            String why = mdc.unusableReason(v);
            if (why != null) {
                bad++;
                System.out.println("  " + v.instrument + ": NOT tradable - " + why);
            }
        }
        System.out.println(bad == 0 ? "CHECK OK: both sessions logged on, every instrument has a book"
                : "CHECK: both sessions logged on, " + bad + " instrument(s) without a usable book");
        return bad == 0 ? 0 : 1;
    }

    /** Check the book, price the order, send it. Orders that cannot be priced are not sent (ERROR). */
    private OrderResult sendOne(TesterConfig cfg, MarketDataClient mdc, TradeClient trade, PriceResolver resolver,
                                OrderRequest o) {
        // Reconnects follow the same order as the start: market logon, trade logon, then (re-)subscribe.
        boolean marketReconnected = false;
        boolean tradeReconnected;
        if (mdc != null) {
            try {
                marketReconnected = mdc.ensureConnected();
            } catch (Exception e) {
                System.out.println("[market] reconnect failed: " + msg(e));
            }
        }
        try {
            tradeReconnected = trade.ensureConnected();
        } catch (Exception e) {
            System.out.println("[trade] NOT sending " + o.describe() + ": trade session down: " + msg(e));
            return new OrderResult(o).fail(OrderStatus.ERROR, "not sent: trade session down: " + msg(e));
        }
        BookView book = null;
        String unusable = null;
        if (mdc != null) {
            try {
                mdc.recover(marketReconnected, tradeReconnected, cfg.ws.market.waitForBookMs);
            } catch (Exception e) {
                System.out.println("[market] re-subscribe failed: " + msg(e));
            }
            book = mdc.view(o.instrument.symbol);
            unusable = mdc.unusableReason(book);
        }
        boolean bookPriced = "pending-book".equals(o.priceSource);
        if (unusable != null && (bookPriced || cfg.ws.market.requireBook)) {
            System.out.println("[trade] NOT sending " + o.describe() + ": " + unusable);
            return new OrderResult(o.withPricing(o.price, o.priceSource, book)).fail(OrderStatus.ERROR, "not sent: " + unusable);
        }
        OrderRequest priced;
        try {
            priced = resolver.resolve(o, unusable == null ? book : null);
        } catch (IllegalStateException e) {
            System.out.println("[trade] NOT sending " + o.describe() + ": " + e.getMessage());
            return new OrderResult(o.withPricing(null, o.priceSource, book)).fail(OrderStatus.ERROR, "not sent: " + e.getMessage());
        }
        System.out.println("[trade] sending " + priced.describe() + "  clOrdId=" + priced.clOrdId
                + (priced.book == null ? "" : "  [" + priced.book.summary() + "]"));
        return trade.send(priced, cfg.run.orderTimeoutMs);
    }

    /** When maxOrders cuts the list short, say what is left out (nothing is silently uncovered). */
    static String capNote(List<OrderRequest> all, List<OrderRequest> orders, int maxOrders) {
        if (orders.size() >= all.size()) return null;
        java.util.Set<String> inst = new java.util.LinkedHashSet<>(), sides = new java.util.LinkedHashSet<>(),
                scen = new java.util.LinkedHashSet<>();
        for (OrderRequest o : all) {
            inst.add(o.instrument.symbol);
            sides.add(o.side);
            scen.add(o.label());
        }
        for (OrderRequest o : orders) {
            inst.remove(o.instrument.symbol);
            sides.remove(o.side);
            scen.remove(o.label());
        }
        StringBuilder sb = new StringBuilder("capped at maxOrders=" + maxOrders + " of " + all.size() + " generated orders");
        if (!inst.isEmpty()) sb.append("; instruments never sent: ").append(inst);
        if (!sides.isEmpty()) sb.append("; sides never sent: ").append(sides);
        if (!scen.isEmpty()) sb.append("; scenarios never sent: ").append(scen);
        sb.append(" (raise --max-orders, 0 = no cap, or narrow with --types/--instruments/--sides)");
        return sb.toString();
    }

    private static List<OrderResult> failAll(List<OrderRequest> orders, String why) {
        System.out.println("CONNECT FAILED: " + why);
        List<OrderResult> results = new ArrayList<>();
        for (OrderRequest o : orders) results.add(new OrderResult(o).fail(OrderStatus.ERROR, why));
        return results;
    }

    private static void printBooks(String title, MarketDataClient mdc) {
        System.out.println("=== " + title + " ===");
        for (BookView v : mdc.views()) System.out.println("  " + v.summary());
    }

    private static List<Instrument> instruments(List<OrderRequest> orders) {
        Map<String, Instrument> m = new LinkedHashMap<>();
        for (OrderRequest o : orders) m.putIfAbsent(o.instrument.symbol, o.instrument);
        return new ArrayList<>(m.values());
    }

    private static List<String> symbols(List<OrderRequest> orders) {
        List<String> s = new ArrayList<>();
        for (Instrument i : instruments(orders)) s.add(i.symbol);
        return s;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** expect lookup: "LIMIT_FOK+PREEXEC" -> "PREEXEC" (pre-exec only) -> "LIMIT_FOK" -> "default" -> run defaults. */
    static List<OrderStatus> passStatuses(TesterConfig cfg, OrderRequest o) {
        List<OrderStatus> s = cfg.expect.get(o.label());
        if (s == null && o.preExec) s = cfg.expect.get("PREEXEC");
        if (s == null) s = cfg.expect.get(o.type.name());
        if (s == null) s = cfg.expect.get("default");
        return s != null ? s : cfg.run.defaultPassStatuses;
    }

    /** Returns a reason when this looks like production, or null. */
    static String prodGuard(TesterConfig cfg) {
        String env = cfg.environment == null ? "" : cfg.environment.trim();
        if (cfg.run.nonProdEnvironments.stream().noneMatch(e -> e.equalsIgnoreCase(env))) {
            return "Environment '" + env + "' is not in run.nonProdEnvironments " + cfg.run.nonProdEnvironments + ".";
        }
        for (String url : new String[]{cfg.ws.market.url, cfg.ws.trade.url}) {
            String host;
            try {
                if (url == null || url.contains("${env:")) continue;
                String h = URI.create(url).getHost();
                host = h == null ? "" : h.toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                continue; // reported properly by validate()
            }
            for (String p : cfg.run.prodUrlPatterns) {
                if (!p.isEmpty() && host.contains(p.toLowerCase(Locale.ROOT))) {
                    return "URL host '" + host + "' matches run.prodUrlPatterns entry '" + p + "'.";
                }
            }
        }
        return null;
    }

    /** Run-level settings and expect keys; normalises expect keys. */
    static void validateRun(TesterConfig cfg, int maxOrders) {
        if (cfg.run.orderTimeoutMs <= 0) throw new IllegalArgumentException("run.orderTimeoutMs must be > 0");
        if (cfg.run.repeat < 1) throw new IllegalArgumentException("run.repeat must be >= 1");
        if (maxOrders < 0) throw new IllegalArgumentException("run.maxOrders must be >= 0");
        if (cfg.run.delayBetweenOrdersMs < 0) throw new IllegalArgumentException("run.delayBetweenOrdersMs must be >= 0");
        if (cfg.run.gtdExpireMinutes <= 0) throw new IllegalArgumentException("run.gtdExpireMinutes must be > 0");
        if (cfg.run.aggressiveBps < 0 || cfg.run.passiveBps < 0 || cfg.run.preExecBps < 0) {
            throw new IllegalArgumentException("run.aggressiveBps / passiveBps / preExecBps must be >= 0");
        }
        if (cfg.run.passiveBps >= 10_000) throw new IllegalArgumentException("run.passiveBps must be < 10000");
        if (cfg.ws != null && cfg.ws.market.waitForBookMs < 0) throw new IllegalArgumentException("ws.market.waitForBookMs must be >= 0");
        Map<String, List<OrderStatus>> normalised = new LinkedHashMap<>();
        for (Map.Entry<String, List<OrderStatus>> e : cfg.expect.entrySet()) {
            String key = normaliseExpectKey(e.getKey());
            if (e.getValue() == null || e.getValue().isEmpty()) {
                throw new IllegalArgumentException("expect." + e.getKey() + " must list at least one status");
            }
            if (normalised.put(key, e.getValue()) != null) {
                throw new IllegalArgumentException("expect has '" + key + "' more than once (keys are case-insensitive)");
            }
        }
        cfg.expect = normalised;
    }

    private static String normaliseExpectKey(String raw) {
        String key = raw.trim().toUpperCase(Locale.ROOT);
        if (key.equals("DEFAULT")) return "default";
        if (key.equals("PREEXEC")) return "PREEXEC";
        String base = key.endsWith("+PREEXEC") ? key.substring(0, key.length() - "+PREEXEC".length()) : key;
        try {
            return OrderType.valueOf(base).name() + (base.equals(key) ? "" : "+PREEXEC");
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown expect key '" + raw + "'. Use an order type "
                    + Arrays.toString(OrderType.values()) + ", <TYPE>+PREEXEC, PREEXEC or \"default\"");
        }
    }

    /** Fields some orders depend on must actually be in the order template, or they are silently dropped. */
    private static void checkTemplateFields(TesterConfig cfg, List<OrderRequest> orders) throws Exception {
        String tpl = Templates.MAPPER.writeValueAsString(cfg.ws.trade.orderTemplate);
        if (orders.stream().anyMatch(o -> o.termQuantity) && !tpl.contains("${currency}")) {
            throw new IllegalArgumentException("An instrument uses quantityIn TERM but ws.trade.orderTemplate has no ${currency} "
                    + "field, so the order would silently be sent in base quantity. Add the API's currency field "
                    + "(e.g. \"currency\": \"${currency}\") or remove TERM.");
        }
        if (orders.stream().anyMatch(o -> o.type == OrderType.LIMIT_GTD) && !tpl.contains("${expireTimeMs}")) {
            throw new IllegalArgumentException("LIMIT_GTD is selected but ws.trade.orderTemplate has no ${expireTimeMs} field, "
                    + "so GTD orders would be sent without an expiry. Add the API's expiry field "
                    + "(e.g. \"expireTime\": \"${expireTimeMs}\") or remove LIMIT_GTD.");
        }
        if (orders.stream().anyMatch(o -> o.preExec) && !tpl.contains("${preExec}")) {
            throw new IllegalArgumentException("Pre-exec orders are selected but ws.trade.orderTemplate has no "
                    + "\"preExec\": \"${preExec}\" field, so they would be sent as normal orders.");
        }
    }

    static final class Args {
        static final String USAGE = String.join("\n",
                "Usage: java -jar cypator-order-tester.jar --config <file.json> [options]",
                "",
                "Opens the market-data session, then the trade session, subscribes the instruments,",
                "sends test orders and reports PASS/FAIL per order.",
                "",
                "  --config <file>         JSON config (sessions, instruments, sizes, prices, order types)",
                "  --market-url <wss://..> override ws.market.url",
                "  --trade-url <wss://...> override ws.trade.url",
                "  --no-market-data        skip the market session (only with priceMode \"config\")",
                "  --max-orders <n>        cap for this run (overrides run.maxOrders; 0 = no cap)",
                "  --types <t,...>         only these order types: " + Arrays.toString(OrderType.values()),
                "  --pre-exec <mode>       include (default) | none | only - pre-execution orders (preExecOrderTypes)",
                "  --instruments <s,...>   only these symbols, e.g. XRP/USD,USDC/USD",
                "  --sides <BUY|SELL>      only this side",
                "  --repeat <n>            send each combination n times (overrides run.repeat)",
                "  --timeout <ms>          per-order timeout (overrides run.orderTimeoutMs)",
                "  --dry-run               print the orders and the exact JSON, connect nothing",
                "  --check                 connect + log on both sessions, subscribe, show the books - send NO orders",
                "  --allow-prod            required when the environment / URLs look like production",
                "  --help",
                "",
                "Exit code: 0 all passed, 1 some failed, 2 usage/config error or blocked by the prod guard.");

        String config;
        String marketUrl;
        String tradeUrl;
        boolean noMarketData;
        Integer maxOrders;
        Integer repeat;
        Long timeoutMs;
        PreExecFilter preExec = PreExecFilter.INCLUDE;
        Set<OrderType> types = new LinkedHashSet<>();
        Set<String> instruments = new LinkedHashSet<>();
        Set<String> sides = new LinkedHashSet<>();
        boolean dryRun;
        boolean check;
        boolean allowProd;
        boolean help;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String k = argv[i];
                switch (k) {
                    case "--config": a.config = val(argv, ++i, k); break;
                    case "--market-url": a.marketUrl = val(argv, ++i, k); break;
                    case "--trade-url": a.tradeUrl = val(argv, ++i, k); break;
                    case "--no-market-data": a.noMarketData = true; break;
                    case "--max-orders": a.maxOrders = intVal(argv, ++i, k, 0); break;
                    case "--repeat": a.repeat = intVal(argv, ++i, k, 1); break;
                    case "--timeout": a.timeoutMs = (long) intVal(argv, ++i, k, 1); break;
                    case "--pre-exec": {
                        String v = val(argv, ++i, k).trim().toUpperCase(Locale.ROOT);
                        try {
                            a.preExec = PreExecFilter.valueOf(v);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("--pre-exec takes include, none or only, got '" + argv[i] + "'");
                        }
                        break;
                    }
                    case "--types":
                        for (String t : list(val(argv, ++i, k), k)) {
                            try {
                                a.types.add(OrderType.valueOf(t.toUpperCase(Locale.ROOT)));
                            } catch (IllegalArgumentException e) {
                                throw new IllegalArgumentException("Unknown order type '" + t + "'. Valid: "
                                        + Arrays.toString(OrderType.values()));
                            }
                        }
                        break;
                    case "--instruments":
                        for (String s : list(val(argv, ++i, k), k)) a.instruments.add(s.toUpperCase(Locale.ROOT));
                        break;
                    case "--sides":
                        for (String s : list(val(argv, ++i, k), k)) {
                            String side = s.toUpperCase(Locale.ROOT);
                            if (!side.equals("BUY") && !side.equals("SELL")) throw new IllegalArgumentException("--sides takes BUY and/or SELL, got '" + s + "'");
                            a.sides.add(side);
                        }
                        break;
                    case "--dry-run": a.dryRun = true; break;
                    case "--check": a.check = true; break;
                    case "--allow-prod": a.allowProd = true; break;
                    case "--help":
                    case "-h": a.help = true; break;
                    default: throw new IllegalArgumentException("Unknown option: " + k);
                }
            }
            if (!a.help && a.config == null) throw new IllegalArgumentException("--config is required");
            return a;
        }

        private static String val(String[] argv, int i, String key) {
            if (i >= argv.length) throw new IllegalArgumentException(key + " needs a value");
            return argv[i];
        }

        private static int intVal(String[] argv, int i, String key, int min) {
            String v = val(argv, i, key);
            try {
                int n = Integer.parseInt(v.trim());
                if (n < min) throw new NumberFormatException();
                return n;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " needs a whole number >= " + min + ", got '" + v + "'");
            }
        }

        /** A filter value must name at least one item - an empty value would silently disable the filter. */
        private static List<String> list(String s, String key) {
            List<String> out = new ArrayList<>();
            for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
            if (out.isEmpty()) throw new IllegalArgumentException(key + " needs at least one value");
            return out;
        }
    }
}
