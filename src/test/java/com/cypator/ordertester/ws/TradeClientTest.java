package com.cypator.ordertester.ws;

import com.cypator.ordertester.TestConfigs;
import com.cypator.ordertester.config.TesterConfig;
import com.cypator.ordertester.config.TesterConfig.Instrument;
import com.cypator.ordertester.mock.MockWsServer;
import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderResult;
import com.cypator.ordertester.model.OrderStatus;
import com.cypator.ordertester.model.OrderType;
import com.cypator.ordertester.util.Templates;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the real trade session against the bundled MockWsServer (/trade path). */
class TradeClientTest {
    private MockWsServer mock;
    private TesterConfig cfg;

    @BeforeEach
    void start() throws Exception {
        mock = new MockWsServer(0, "key123", "sekret");
        cfg = TestConfigs.localMock(mock.port());
    }

    @AfterEach
    void stop() throws Exception {
        mock.close();
    }

    private static OrderRequest order(String symbol, OrderType type, String side, String qty, String price) {
        return order(symbol, type, false, side, qty, price);
    }

    private static OrderRequest order(String symbol, OrderType type, boolean preExec, String side, String qty, String price) {
        Instrument ins = new Instrument();
        ins.symbol = symbol;
        return new OrderRequest(1, ins, type, preExec, side, new BigDecimal(qty), price == null ? null : new BigDecimal(price),
                price == null ? "none" : "config", null, ins.base(), false,
                "T-" + symbol.replace('/', '_') + "-" + type + "-" + System.nanoTime(), null, java.util.Collections.singletonMap("account", null));
    }

    private OrderResult send(TradeClient t, OrderRequest o) {
        return t.send(o, 3000);
    }

    @Test
    void fillsKillsPartialsRejectsAndCancels() throws Exception {
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();

            OrderResult fill = send(t, order("XRP/USD", OrderType.LIMIT_FOK, "BUY", "10", "3.5"));
            assertEquals(OrderStatus.FILLED, fill.status, fill.text);
            assertEquals(0, fill.filledQty.compareTo(new BigDecimal("10")), "cumQuantity spelling must be read");
            assertEquals(0, fill.avgPrice.compareTo(new BigDecimal("2.41")), "fills at the touch");
            assertTrue(fill.orderId.startsWith("A34"));
            assertEquals(2, fill.events.size(), "order echo (ACK) + trade");

            OrderResult mkt = send(t, order("XRP/USD", OrderType.MARKET_IOC, "SELL", "10", null));
            assertEquals(OrderStatus.FILLED, mkt.status);
            assertTrue(mkt.events.get(0).contains("\"orderType\":\"MARKET\""));

            OrderResult partial = send(t, order("USDC/USD", OrderType.MARKET_IOC, "BUY", "1000", null));
            assertEquals(OrderStatus.PARTIAL_FILL, partial.status);
            assertEquals(0, partial.filledQty.compareTo(new BigDecimal("500")));

            OrderResult killed = send(t, order("USDC/USD", OrderType.LIMIT_FOK, "BUY", "1000", "1.01"));
            assertEquals(OrderStatus.CANCELED, killed.status);
            assertEquals(0, killed.filledQty.signum());

            OrderResult noCross = send(t, order("XRP/USD", OrderType.LIMIT_IOC, "BUY", "10", "2.00"));
            assertEquals(OrderStatus.CANCELED, noCross.status, "limit below the ask does not fill");

            OrderResult rejected = send(t, order("BAD/SYM", OrderType.LIMIT_FOK, "BUY", "1", "1"));
            assertEquals(OrderStatus.REJECTED, rejected.status);
            assertTrue(rejected.text.contains("50400"));

            OrderResult gtc = send(t, order("XRP/USD", OrderType.LIMIT_GTC, "BUY", "10", "1.0"));
            assertEquals(OrderStatus.CANCELED, gtc.status, gtc.text);
            JsonNode sent = mock.orders.get(mock.orders.size() - 1);
            assertEquals("GOOD_TILL_CANCEL", sent.path("tif").asText(), "tifMap maps GTC to the API value");
        }
    }

    @Test
    void preExecOrderCarriesTheFlagAndFillsAtThePriceSent() throws Exception {
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            OrderResult r = send(t, order("XRP/USD", OrderType.LIMIT_FOK, true, "BUY", "10", "2.4050"));
            assertEquals(OrderStatus.FILLED, r.status, r.text);
            assertEquals(0, r.avgPrice.compareTo(new BigDecimal("2.4050")));
            assertTrue(mock.orders.get(0).path("preExec").asBoolean());
            OrderResult normal = send(t, order("XRP/USD", OrderType.LIMIT_FOK, false, "BUY", "10", "3.5"));
            assertEquals(OrderStatus.FILLED, normal.status);
            assertTrue(mock.orders.get(1).has("preExec") && !mock.orders.get(1).path("preExec").asBoolean());
        }
    }

    @Test
    void orderEchoWithErrorCodeIsRejected() throws Exception {
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            OrderResult r = send(t, order("XRP/USD", OrderType.LIMIT_GTD, "BUY", "10", "1.0"));
            assertEquals(OrderStatus.REJECTED, r.status, r.text);
            assertTrue(r.text.contains("50304"), r.text);
            assertTrue(!r.text.contains("ON THE BOOK"), "refused at entry, never acked: " + r.text);
        }
    }

    @Test
    void restingOrderWithoutCancelTemplateIsReportedAsLeftOnBook() throws Exception {
        cfg.ws.trade.cancelTemplate = null;
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            OrderResult gtc = send(t, order("XRP/USD", OrderType.LIMIT_GTC, "BUY", "10", "1.0"));
            assertEquals(OrderStatus.RESTING, gtc.status);
            assertTrue(gtc.text.contains("LEFT ON BOOK"));
        }
    }

    @Test
    void refusedCancelFailsAndWarnsTheOrderIsStillOnTheBook() throws Exception {
        // cancel the wrong id -> {"op":"cancel_order","arg":{"clOrderId":"<id>-C","code":"50400",...}}
        cfg.ws.trade.cancelTemplate = Templates.MAPPER.readTree(
                "{\"op\":\"cancel_order\",\"arg\":{\"clOrderId\":\"${cancelClOrdId}\",\"instrument\":\"${instrument}\"}}");
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            OrderResult gtc = send(t, order("XRP/USD", OrderType.LIMIT_GTC, "BUY", "10", "1.0"));
            assertEquals(OrderStatus.REJECTED, gtc.status);
            assertTrue(gtc.text.contains("cancel refused"), gtc.text);
            assertTrue(gtc.text.contains("ON THE BOOK"), gtc.text);
        }
    }

    @Test
    void silentServerTimesOutAndNextOrderStillWorks() throws Exception {
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            OrderResult silent = t.send(order("SILENT/SYM", OrderType.LIMIT_FOK, "BUY", "1", "1"), 300);
            assertEquals(OrderStatus.TIMEOUT, silent.status);
            assertTrue(silent.text.contains("never acked"));
            assertEquals(OrderStatus.FILLED, send(t, order("XRP/USD", OrderType.LIMIT_FOK, "BUY", "1", "3.5")).status);
        }
    }

    @Test
    void lateUncorrelatedReplyAfterTimeoutCannotFailTheNextOrder() throws Exception {
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            OrderResult slow = t.send(order("SLOW/SYM", OrderType.LIMIT_FOK, "BUY", "1", "1"), 200);
            assertEquals(OrderStatus.TIMEOUT, slow.status);
            // The mock sends its uncorrelated 50400 ~400 ms after the timeout. If the socket were reused it
            // would be credited to this (silent) order and turn it REJECTED; it must stay TIMEOUT.
            OrderResult next = t.send(order("SILENT/SYM", OrderType.LIMIT_FOK, "BUY", "1", "1"), 1500);
            assertEquals(OrderStatus.TIMEOUT, next.status, next.text);
            assertTrue(mock.connections.get() >= 2, "socket must be replaced after a timeout");
            assertEquals(OrderStatus.FILLED, send(t, order("XRP/USD", OrderType.LIMIT_IOC, "BUY", "1", "3.5")).status);
        }
    }

    @Test
    void errorOpWithClOrdIdButNoCodeIsRejectedNotTimedOut() throws Exception {
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            OrderResult r = send(t, order("ERRCL/SYM", OrderType.LIMIT_FOK, "BUY", "1", "1"));
            assertEquals(OrderStatus.REJECTED, r.status, r.text);
            assertTrue(r.text.contains("INVALID_TIF"), r.text);
        }
    }

    @Test
    void droppedSocketFailsTheOrderAndReconnects() throws Exception {
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            OrderResult dropped = send(t, order("DROP/SYM", OrderType.LIMIT_FOK, "BUY", "1", "1"));
            assertEquals(OrderStatus.ERROR, dropped.status, dropped.text);
            OrderResult next = send(t, order("XRP/USD", OrderType.LIMIT_FOK, "BUY", "1", "3.5"));
            assertEquals(OrderStatus.FILLED, next.status, next.text);
            assertEquals(2, mock.tradeLogons.get());
        }
    }

    @Test
    void noReconnectWhenDisabled() throws Exception {
        cfg.ws.reconnect = false;
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            send(t, order("DROP/SYM", OrderType.LIMIT_FOK, "BUY", "1", "1"));
            OrderResult next = send(t, order("XRP/USD", OrderType.LIMIT_FOK, "BUY", "1", "3.5"));
            assertEquals(OrderStatus.ERROR, next.status);
            assertTrue(next.text.contains("reconnect=false"), next.text);
        }
    }

    @Test
    void logonCarriesServerAndClientAndWrongSecretFails() throws Exception {
        cfg.ws.secret = "wrong";
        try (TradeClient t = new TradeClient(cfg.ws)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, t::connect);
            assertTrue(e.getMessage().contains("50103"), e.getMessage());
        }
        cfg.ws.secret = "sekret";
        cfg.ws.trade.client = "";
        try (TradeClient t = new TradeClient(cfg.ws)) {
            assertThrows(IllegalArgumentException.class, t::validate, "client id is required by the logon template");
        }
    }

    @Test
    void unsetCredentialIsAConfigErrorBeforeAnySocketIsOpened() throws Exception {
        cfg.ws.apiKey = "${env:OT_DEFINITELY_NOT_SET}";
        try (TradeClient t = new TradeClient(cfg.ws)) {
            assertThrows(IllegalArgumentException.class, t::validate);
            assertThrows(IllegalArgumentException.class, t::connect);
            assertEquals(0, mock.connections.get());
        }
    }

    @Test
    void connectionRefusedIsAClearError() throws Exception {
        int port = mock.port();
        mock.close();
        cfg.ws.trade.url = "ws://localhost:" + port + "/trade";
        cfg.ws.logonTimeoutMs = 2000;
        try (TradeClient t = new TradeClient(cfg.ws)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, t::connect);
            assertTrue(e.getMessage().contains("connect"), e.getMessage());
        }
    }

    @Test
    void pingsAreSentAndTheServersPingReplyIsNotAnswered() throws Exception {
        cfg.ws.pingIntervalMs = 100;
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            Thread.sleep(450);
            int n = mock.pings.get();
            assertTrue(n >= 2 && n <= 6, "pings=" + n + " (a reply loop would explode this)");
        }
    }

    @Test
    void pingIntervalOutsideTheApiLimitsIsRejectedForRealHosts() throws Exception {
        cfg.ws.trade.url = "wss://wsapi14.rippleuat.cypator.net:12007/trade";
        for (long bad : new long[]{5_000, 30_000, 0}) {
            cfg.ws.pingIntervalMs = bad;
            try (TradeClient t = new TradeClient(cfg.ws)) {
                assertThrows(IllegalArgumentException.class, t::validate, "pingIntervalMs=" + bad);
            }
        }
        cfg.ws.pingIntervalMs = 20_000;
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.validate();
        }
    }

    @Test
    void pingsResumeRightAfterALongOrderFlight() throws Exception {
        cfg.ws.pingIntervalMs = 100;
        try (TradeClient t = new TradeClient(cfg.ws)) {
            t.connect();
            Thread.sleep(250);
            int before = mock.pings.get();
            assertTrue(before >= 1, "pings before the flight: " + before);
            // a 600 ms order: no ping during the flight, but the skipped ticks are caught up right after it
            OrderResult r = t.send(order("DELAY/SYM", OrderType.LIMIT_FOK, "BUY", "1", "1"), 3000);
            assertEquals(OrderStatus.FILLED, r.status, r.text);
            int afterFlight = mock.pings.get();
            assertTrue(afterFlight <= before + 1, "no pings during the flight: " + before + " -> " + afterFlight);
            Thread.sleep(250);
            assertTrue(mock.pings.get() > afterFlight, "ping resumed after the flight");
        }
    }
}
