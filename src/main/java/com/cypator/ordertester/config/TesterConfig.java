package com.cypator.ordertester.config;

import com.cypator.ordertester.model.OrderStatus;
import com.cypator.ordertester.model.OrderType;
import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root of the JSON config file. Every string may contain ${env:NAME} so secrets stay out of
 * the file. Keys starting with "_" are comments and are ignored everywhere.
 *
 * Message formats follow the Ripple/Cypator WebSocket Taker API (docs.liquidity.ripple.com):
 * a market session (subscribe / snapshot) and a separate trade session (order / cancel_order / trade).
 */
public class TesterConfig {

    /** Environment label (uat, rippleuat, gcp-uat ...). Anything not in run.nonProdEnvironments needs --allow-prod. */
    public String environment;
    public Run run = new Run();
    public List<Instrument> instruments = new ArrayList<>();
    /** Order types to exercise (each instrument may override). */
    public List<OrderType> orderTypes = new ArrayList<>();
    /** Order types that are ALSO sent as pre-execution orders ("preExec": true). Empty = no pre-exec orders. */
    public List<OrderType> preExecOrderTypes = new ArrayList<>();
    /** Extra template variables for every order (e.g. account, baseChain); a null value drops the field. */
    public Map<String, Object> orderVars = new LinkedHashMap<>();
    /**
     * Scenario label (LIMIT_FOK, LIMIT_FOK+PREEXEC, PREEXEC, or "default") -> statuses that count as PASS.
     * Falls back to run.defaultPassStatuses.
     */
    public Map<String, List<OrderStatus>> expect = new LinkedHashMap<>();
    public WsConfig ws;

    public static class Run {
        /** Hard cap on orders per run; 0 = no cap. */
        public int maxOrders = 10;
        /** How many times each (instrument, size, side, type) combination is sent. */
        public int repeat = 1;
        public long delayBetweenOrdersMs = 500;
        public long orderTimeoutMs = 10_000;
        public List<String> sides = new ArrayList<>(List.of("BUY", "SELL"));
        public String reportDir = "reports";
        /** Prefix of every generated ClOrdID, so test orders are easy to find in logs and the DB. */
        public String clOrdIdPrefix = "OT";
        public boolean stopOnFailure = false;
        /** Expiry of LIMIT_GTD orders, minutes from now (template variable ${expireTimeMs}). */
        public int gtdExpireMinutes = 60;
        /** "config" = instruments[].limitPrice, "book" = derived from the live market-data book at send time. */
        public String priceMode = "config";
        /** Book mode: IOC/FOK limit prices cross the touch by this many basis points (BUY above ask, SELL below bid). */
        public int aggressiveBps = 20;
        /** Book mode: GTC/GTD prices sit this many bps away from the touch so they rest (BUY below bid, SELL above ask). */
        public int passiveBps = 100;
        /** Pre-exec orders without a fixed preExecPrice are priced at the touch plus/minus this many bps. */
        public int preExecBps = 0;
        /**
         * A pre-exec order executes at the price sent: refuse to send one priced further than this from the live
         * touch (default 500 bps = the trade-persistor 5% price band). 0 = no check.
         */
        public int preExecMaxDeviationBps = 500;
        /** Send a price on MARKET orders (the API marks price as required): the touch, else limitPrice, else none. */
        public boolean sendPriceOnMarket = true;
        public List<String> nonProdEnvironments = new ArrayList<>(List.of("uat", "rippleuat", "gcp-uat", "dev", "local"));
        /** ws URLs whose host contains any of these also need --allow-prod (ripple prod = wsapiN.ripple.cypator.com). */
        public List<String> prodUrlPatterns = new ArrayList<>(List.of("ripple.cypator.com", "prod"));
        public List<OrderStatus> defaultPassStatuses = new ArrayList<>(List.of(
                OrderStatus.FILLED, OrderStatus.PARTIAL_FILL, OrderStatus.CANCELED,
                OrderStatus.RESTING, OrderStatus.EXPIRED));
    }

    public static class Instrument {
        /** BASE/TERM, as in instruments.short_name, e.g. "XRP/USD". */
        public String symbol;
        public Integer instrumentId;
        public List<BigDecimal> sizes = new ArrayList<>();
        /** Limit price per side: {"BUY": 2.6, "SELL": 2.4}. Required for LIMIT_* types when priceMode=config. */
        public Map<String, BigDecimal> limitPrice = new LinkedHashMap<>();
        /** Override of run.priceMode for this instrument. */
        public String priceMode;
        /**
         * Fixed prices for PRE-EXEC orders {"BUY": .., "SELL": ..}. Pre-exec orders never use limitPrice (which is
         * usually deliberately off-market); without preExecPrice they are priced from the live touch.
         */
        public Map<String, BigDecimal> preExecPrice = new LinkedHashMap<>();
        /** Decimals for book-derived prices; default = the decimals the book itself uses. */
        public Integer priceDecimals;
        /** BASE and/or TERM: which currency the quantity is expressed in (template variable ${currency}). */
        public List<String> quantityIn = new ArrayList<>(List.of("BASE"));
        /** Optional per-instrument override of orderTypes. */
        public List<OrderType> orderTypes;
        /** Optional per-instrument override of preExecOrderTypes. */
        public List<OrderType> preExecOrderTypes;
        /** Extra template variables for this instrument (override orderVars). */
        public Map<String, Object> vars = new LinkedHashMap<>();

        public String base() {
            return symbol.contains("/") ? symbol.substring(0, symbol.indexOf('/')) : symbol;
        }

        public String term() {
            return symbol.contains("/") ? symbol.substring(symbol.indexOf('/') + 1) : "";
        }
    }

    /** Settings shared by the market and the trade session. */
    public static class WsConfig {
        /** Server identifier (logon arg.server), e.g. TRIPPLEWSAPIUAT5 - the wsapiN number does not map to it. */
        public String server;
        /** Default login for both sessions (each session may override). */
        public String apiKey;
        /** Password/passphrase: the HMAC-SHA256 key for the logon signature (never sent). */
        public String secret;
        /** sign = Base64(HMAC-SHA256(timestamp + signPath, secret)); timestamp = the logon "ts". */
        public String signPath = "/verify";
        /** Unit of the logon timestamp: "ms" (epoch millis, per the API docs) or "s". */
        public String timestampUnit = "ms";
        public long logonTimeoutMs = 10_000;
        /** The API needs a ping at least every 30 s and allows at most 1 per 15 s. */
        public long pingIntervalMs = 20_000;
        /** Reconnect + re-logon (and re-subscribe on the market session) when the server closed a socket. */
        public boolean reconnect = true;
        public Map<String, String> headers = new LinkedHashMap<>();
        /** Variables: ${apiKey} ${sign} ${server} ${client} ${timestamp} (Long) ${nowMs}. */
        public JsonNode logonTemplate;
        public JsonNode pingTemplate;
        public boolean dropNullFields = true;
        /** Common reply fields. */
        public String opPointer = "/op";
        public String codePointer = "/arg/code";
        public String errorPointer = "/arg/errMsg";
        public List<String> okCodes = new ArrayList<>(List.of("0"));
        public List<String> logonOps = new ArrayList<>(List.of("logon"));
        /** Heartbeat replies; the API answers a ping with {"op":"ping"}. Never answered, never logged. */
        public List<String> heartbeatOps = new ArrayList<>(List.of("ping", "pong"));
        /** Handled normally but not echoed to the console (snapshots stream continuously). */
        public List<String> quietOps = new ArrayList<>(List.of("snapshot"));
        public MarketConfig market = new MarketConfig();
        public TradeConfig trade = new TradeConfig();
    }

    /** One WebSocket session (market or trade). */
    public static class SessionConfig {
        /** e.g. wss://wsapi14.rippleuat.cypator.net:12007/market or .../trade */
        public String url;
        /** Client identifier for this session (logon arg.client), e.g. RIPPLEMC2 (market) / RIPPLETC2 (trade). */
        public String client;
        /** Optional overrides of ws.apiKey / ws.secret / ws.server for this session. */
        public String apiKey;
        public String secret;
        public String server;
        public Map<String, String> headers = new LinkedHashMap<>();
    }

    public static class MarketConfig extends SessionConfig {
        /** false = no market session at all (only possible with priceMode=config everywhere). */
        public boolean enabled = true;
        /** Variables: ${instrument} ${instrumentId} ${subscriptionId} ${nowMs}. */
        public JsonNode subscribeTemplate;
        public JsonNode unsubscribeTemplate;
        /** How long to wait after subscribing for the first snapshot of every instrument. */
        public long waitForBookMs = 10_000;
        /** An order whose instrument has no book (no snapshot / subscribe error) fails as ERROR without being sent. */
        public boolean requireBook = true;
        /** If > 0, a book older than this is treated as missing (stale). */
        public long maxBookAgeMs = 0;
        /** Prefix of generated subscription ids. */
        public String subscriptionIdPrefix = "OT";
        public List<String> snapshotOps = new ArrayList<>(List.of("snapshot"));
        public List<String> subscribeOps = new ArrayList<>(List.of("subscribe"));
        public String instrumentPointer = "/arg/instrument";
        public String subscriptionIdPointer = "/arg/subscriptionId";
        public String bidsPointer = "/data/0/bids";
        public String asksPointer = "/data/0/asks";
    }

    public static class TradeConfig extends SessionConfig {
        /** Variables: see OrderRequest.templateVars (clOrdId, instrument, side, quantity, price, tif, preExec ...). */
        public JsonNode orderTemplate;
        /** Variables: order variables + ${origClOrdId} ${cancelClOrdId} ${orderId}. */
        public JsonNode cancelTemplate;
        /** After a GTC/GTD order is acknowledged, cancel it and wait for the cancel. */
        public boolean cancelRestingOrders = true;
        /** Tester time-in-force -> wire value. The API uses GOOD_TILL_CANCEL for GTC. */
        @JsonMerge
        public Map<String, String> tifMap = new LinkedHashMap<>(Map.of(
                "IOC", "IOC", "FOK", "FOK", "GTC", "GOOD_TILL_CANCEL", "GTD", "GTD"));
        public TradeResponse response = new TradeResponse();
    }

    /** JSON pointers (RFC 6901) into trade-session replies. A list = first pointer present wins. */
    public static class TradeResponse {
        public String clOrdIdPointer = "/arg/clOrderId";
        public String statePointer = "/arg/state";
        public String orderIdPointer = "/arg/orderId";
        /** The docs' example says cumQty/lastQty, their field table cumQuantity/lastQuantity - accept both. */
        public List<String> cumQtyPointers = new ArrayList<>(List.of("/arg/cumQty", "/arg/cumQuantity"));
        public List<String> lastQtyPointers = new ArrayList<>(List.of("/arg/lastQty", "/arg/lastQuantity"));
        public List<String> lastPricePointers = new ArrayList<>(List.of("/arg/lastPrice"));
        public List<String> avgPricePointers = new ArrayList<>(List.of("/arg/avgPrice"));
        /** Execution reports. */
        public List<String> executionOps = new ArrayList<>(List.of("trade"));
        /** The order echo ({"op":"order", ..., "code":0}) = ACK; with a non-zero code = REJECTED. */
        public List<String> ackOps = new ArrayList<>(List.of("order"));
        /** Replies to cancel_order; a non-zero code means the cancel was refused. */
        public List<String> cancelReplyOps = new ArrayList<>(List.of("cancel_order"));
        public List<String> errorOps = new ArrayList<>(List.of("invalid", "error", "reject"));
        /**
         * API "state" value (case-insensitive) -> ACK / PARTIAL / FILLED / CANCELED / REJECTED / EXPIRED.
         * Entries in the config are MERGED into these defaults. Unknown states are logged and ignored.
         */
        @JsonMerge
        public Map<String, String> stateMap = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("NEW", "ACK"), Map.entry("ACCEPTED", "ACK"), Map.entry("ACKNOWLEDGED", "ACK"),
                Map.entry("OPEN", "ACK"), Map.entry("PENDING_NEW", "ACK"),
                Map.entry("PARTIALLY_FILLED", "PARTIAL"), Map.entry("PARTIAL", "PARTIAL"),
                Map.entry("FILLED", "FILLED"),
                Map.entry("CANCELED", "CANCELED"), Map.entry("CANCELLED", "CANCELED"),
                Map.entry("REJECTED", "REJECTED"), Map.entry("EXPIRED", "EXPIRED")));
    }
}
