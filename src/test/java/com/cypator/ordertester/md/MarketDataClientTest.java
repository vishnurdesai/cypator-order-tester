package com.cypator.ordertester.md;

import com.cypator.ordertester.TestConfigs;
import com.cypator.ordertester.config.TesterConfig;
import com.cypator.ordertester.config.TesterConfig.Instrument;
import com.cypator.ordertester.mock.MockWsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the real market session against the bundled MockWsServer (/market path). */
class MarketDataClientTest {
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

    private static List<Instrument> instruments(String... symbols) {
        List<Instrument> out = new ArrayList<>();
        for (String s : symbols) {
            Instrument i = new Instrument();
            i.symbol = s;
            out.add(i);
        }
        return out;
    }

    @Test
    void subscribesAndBuildsTopOfBook() throws Exception {
        try (MarketDataClient md = new MarketDataClient(cfg.ws)) {
            md.connect();
            md.subscribe(instruments("XRP/USD", "USDC/USD", "BAD/SYM", "EMPTY/SYM"));
            assertTrue(md.awaitBooks(3000), "every instrument answers (snapshot or error)");

            BookView xrp = md.view("XRP/USD");
            assertTrue(xrp.hasSnapshot());
            assertEquals(0, xrp.bid.compareTo(new BigDecimal("2.40")));
            assertEquals(0, xrp.ask.compareTo(new BigDecimal("2.41")));
            assertEquals(2, xrp.bidLevels);
            assertEquals(4, xrp.decimals);
            assertNull(md.unusableReason(xrp));

            BookView bad = md.view("BAD/SYM");
            assertNotNull(bad.error);
            assertTrue(md.unusableReason(bad).contains("50202"), md.unusableReason(bad));

            BookView empty = md.view("EMPTY/SYM");
            assertTrue(empty.hasSnapshot());
            assertNull(empty.bid);
            assertNull(empty.ask);

            assertNotNull(md.unusableReason(md.view("NOT/SUBSCRIBED")));
            assertEquals(1, mock.marketLogons.get());
            assertEquals(4, mock.subscribes.size());
            assertEquals("SPOT", mock.subscribes.get(0).path("arg").path("typeBook").asText());
            assertEquals("snapshot", mock.subscribes.get(0).path("arg").path("subscriptionRequestType").asText());
        }
    }

    @Test
    void snapshotsKeepStreamingAndAgeIsTracked() throws Exception {
        try (MarketDataClient md = new MarketDataClient(cfg.ws)) {
            md.connect();
            md.subscribe(instruments("USDC/USD"));
            md.awaitBooks(2000);
            long first = md.view("USDC/USD").updates;
            Thread.sleep(700);
            BookView v = md.view("USDC/USD");
            assertTrue(v.updates > first, "updates " + first + " -> " + v.updates);
            assertTrue(v.ageMs >= 0 && v.ageMs < 1000, "age " + v.ageMs);
        }
    }

    @Test
    void staleBookIsUnusable() throws Exception {
        cfg.ws.market.maxBookAgeMs = 1;
        try (MarketDataClient md = new MarketDataClient(cfg.ws)) {
            md.connect();
            md.subscribe(instruments("XRP/USD"));
            md.awaitBooks(2000);
            String why = null;
            for (int i = 0; i < 20 && why == null; i++) {   // snapshots arrive every 200 ms; catch one between them
                Thread.sleep(25);
                why = md.unusableReason(md.view("XRP/USD"));
            }
            assertNotNull(why);
            assertTrue(why.contains("stale"), why);
        }
    }

    @Test
    void reconnectRelogsMarketThenTradeThenResubscribesAndWaitsForAFreshBook() throws Exception {
        try (MarketDataClient md = new MarketDataClient(cfg.ws);
             com.cypator.ordertester.ws.TradeClient trade = new com.cypator.ordertester.ws.TradeClient(cfg.ws)) {
            md.connect();
            trade.connect();
            md.subscribe(instruments("XRP/USD"));
            md.awaitBooks(2000);
            String oldSub = mock.subscribes.get(0).path("arg").path("subscriptionId").asText();

            mock.dropAll();                     // server restart: both sockets go
            Thread.sleep(200);
            mock.events.clear();
            assertTrue(md.ensureConnected(), "market reconnects first");
            assertTrue(trade.ensureConnected(), "then trade");
            md.recover(true, true, 2000);

            assertEquals(List.of("market", "trade", "subscribe:XRP/USD"), mock.events.subList(0, 3));
            String newSub = mock.subscribes.get(mock.subscribes.size() - 1).path("arg").path("subscriptionId").asText();
            assertTrue(!newSub.equals(oldSub), "new subscription id");
            BookView v = md.view("XRP/USD");
            assertTrue(v.fresh);
            assertNull(md.unusableReason(v));
        }
    }

    @Test
    void resubscribedBookIsNotUsableUntilTheNewSubscriptionAnswers() throws Exception {
        try (MarketDataClient md = new MarketDataClient(cfg.ws);
             com.cypator.ordertester.ws.TradeClient trade = new com.cypator.ordertester.ws.TradeClient(cfg.ws)) {
            md.connect();
            trade.connect();
            md.subscribe(instruments("XRP/USD"));
            md.awaitBooks(2000);
            mock.dropAll();
            Thread.sleep(200);
            md.ensureConnected();
            trade.ensureConnected();
            md.recover(true, true, 0);           // do not wait: the new snapshot has not arrived yet
            String why = md.unusableReason(md.view("XRP/USD"));
            assertNotNull(why, "old snapshot must not be used right after re-subscribing");
        }
    }

    @Test
    void decimalsAreKeptFromTheWire() throws Exception {
        try (MarketDataClient md = new MarketDataClient(cfg.ws)) {
            md.connect();
            md.subscribe(instruments("XRP/USD"));
            md.awaitBooks(2000);
            assertEquals(4, md.view("XRP/USD").decimals, "2.4000 / 2.4100 on the wire = 4 decimals");
        }
    }

    @Test
    void missingSubscribeTemplateIsAConfigError() {
        cfg.ws.market.subscribeTemplate = null;
        assertThrows(IllegalArgumentException.class, () -> new MarketDataClient(cfg.ws));
    }
}
