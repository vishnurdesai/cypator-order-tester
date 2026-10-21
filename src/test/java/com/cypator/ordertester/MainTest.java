package com.cypator.ordertester;

import com.cypator.ordertester.config.TesterConfig;
import com.cypator.ordertester.mock.MockWsServer;
import com.cypator.ordertester.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    private static Main.Args args(String... a) {
        return Main.Args.parse(a);
    }

    /** local-mock.json on the given port; pairs = from, to, from, to ... (each must exist). */
    private static Path writeConfig(Path dir, int port, String... pairs) throws Exception {
        String json = Files.readString(Path.of("config", "local-mock.json"))
                .replace("ws://localhost:19877/", "ws://localhost:" + port + "/")
                .replace("\"run\": {", "\"run\": { \"reportDir\": \"" + dir.toString().replace("\\", "/") + "\",");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i] == null) continue;
            if (!json.contains(pairs[i])) throw new IllegalStateException("test setup: '" + pairs[i] + "' not in local-mock.json");
            json = json.replace(pairs[i], pairs[i + 1]);
        }
        Path f = dir.resolve("cfg-" + System.nanoTime() + ".json");
        Files.writeString(f, json);
        return f;
    }

    @Test
    void endToEndMarketDataThenTradeThenSubscribeThenOrders(@TempDir Path dir) throws Exception {
        try (MockWsServer mock = new MockWsServer(0, "key123", "sekret")) {
            // Expected FAILs: USDC/USD 1000 LIMIT_FOK is killed (thin) x2 sides; BAD/SYM has no market data x2 -> exit 1
            Path cfg = writeConfig(dir, mock.port(), null, null);
            assertEquals(1, new Main().run(args("--config", cfg.toString())));

            assertEquals("market", mock.events.get(0), "market session logs on first: " + mock.events);
            assertEquals("trade", mock.events.get(1), "then the trade session: " + mock.events);
            assertTrue(mock.events.get(2).startsWith("subscribe:"), "then subscribe: " + mock.events);
            assertTrue(mock.orders.stream().noneMatch(o -> o.path("instrument").asText().equals("BAD/SYM")),
                    "an instrument without a book is never sent");
            assertTrue(mock.orders.stream().anyMatch(o -> o.path("preExec").asBoolean()), "pre-exec orders were sent");

            String csv;
            try (var files = Files.list(dir)) {
                Path report = files.filter(p -> p.getFileName().toString().endsWith("-local.csv")).findFirst().orElseThrow();
                csv = Files.readString(report);
            }
            assertTrue(csv.startsWith("seq,clOrdId,instrument,scenario,orderType,preExec,side"));
            assertTrue(csv.contains("LIMIT_FOK+PREEXEC"));
            assertTrue(csv.contains("book-aggressive") && csv.contains("book-passive") && csv.contains("book-touch"));

            // only orders that fill -> exit 0
            assertEquals(0, new Main().run(args("--config", cfg.toString(), "--instruments", "XRP/USD",
                    "--types", "LIMIT_FOK,MARKET_IOC,LIMIT_GTC")));
            // pre-exec only
            int before = mock.orders.size();
            assertEquals(0, new Main().run(args("--config", cfg.toString(), "--pre-exec", "only")));
            assertTrue(mock.orders.subList(before, mock.orders.size()).stream().allMatch(o -> o.path("preExec").asBoolean()));
        }
    }

    @Test
    void checkModeConnectsAndSubscribesButSendsNoOrders(@TempDir Path dir) throws Exception {
        try (MockWsServer mock = new MockWsServer(0, "key123", "sekret")) {
            Path ok = writeConfig(dir, mock.port(), null, null);
            assertEquals(1, new Main().run(args("--config", ok.toString(), "--check")), "BAD/SYM has no book");
            assertEquals(0, new Main().run(args("--config", ok.toString(), "--check", "--instruments", "XRP/USD,USDC/USD")));
            assertTrue(mock.orders.isEmpty(), "check mode never sends an order");
            assertEquals(List.of("market", "trade"), mock.events.subList(0, 2));
            assertTrue(mock.marketLogons.get() >= 2 && mock.tradeLogons.get() >= 2);
        }
    }

    @Test
    void marketDataFailureMeansNoOrderIsSent(@TempDir Path dir) throws Exception {
        try (MockWsServer mock = new MockWsServer(0, "key123", "sekret")) {
            Path cfg = writeConfig(dir, mock.port(), "\"client\": \"MOCKMD\"", "\"client\": \"MOCKMD\", \"apiKey\": \"wrong\"");
            assertEquals(1, new Main().run(args("--config", cfg.toString())));
            assertEquals(0, mock.tradeLogons.get(), "trade session is not opened when market data fails");
            assertTrue(mock.orders.isEmpty());
        }
    }

    @Test
    void noMarketDataRunsConfigPricedOrdersOnly(@TempDir Path dir) throws Exception {
        try (MockWsServer mock = new MockWsServer(0, "key123", "sekret")) {
            Path cfg = writeConfig(dir, mock.port(), null, null);
            assertThrows(IllegalArgumentException.class,
                    () -> new Main().run(args("--config", cfg.toString(), "--no-market-data")), "book mode needs market data");
            assertEquals(0, new Main().run(args("--config", cfg.toString(), "--no-market-data", "--pre-exec", "none", "--instruments", "XRP/USD",
                    "--types", "LIMIT_FOK")));
            assertEquals(0, mock.marketLogons.get());
        }
    }

    @Test
    void prodGuardBlocksByEnvironmentAndEitherUrl(@TempDir Path dir) throws Exception {
        Path prodEnv = writeConfig(dir, 1, "\"environment\": \"local\"", "\"environment\": \"ripple\"");
        assertEquals(2, new Main().run(args("--config", prodEnv.toString())));
        Path cfgFile = writeConfig(dir, 1, null, null);
        assertEquals(2, new Main().run(args("--config", cfgFile.toString(),
                "--market-url", "wss://wsapi12.ripple.cypator.com:12007/market")));

        TesterConfig cfg = TestConfigs.localMock(1);
        assertNull(Main.prodGuard(cfg));
        cfg.ws.trade.url = "wss://wsapi12.ripple.cypator.com:12007/trade";
        assertNotNull(Main.prodGuard(cfg));
        cfg.ws.trade.url = "wss://wsapi14.rippleuat.cypator.net:12007/trade";
        assertNull(Main.prodGuard(cfg));
        cfg.ws.market.url = "wss://wsapi1.gprod.cypator.com:12007/market";
        assertNotNull(Main.prodGuard(cfg));
    }

    @Test
    void dryRunNeverConnects(@TempDir Path dir) throws Exception {
        Path cfg = writeConfig(dir, 1, "\"environment\": \"local\"", "\"environment\": \"ripple\"");
        assertEquals(0, new Main().run(args("--config", cfg.toString(), "--dry-run")));
    }

    @Test
    void templateFieldChecks(@TempDir Path dir) throws Exception {
        Path term = writeConfig(dir, 1, "\"sizes\": [10],", "\"sizes\": [10], \"quantityIn\": [\"TERM\"],");
        assertThrows(IllegalArgumentException.class, () -> new Main().run(args("--config", term.toString(), "--dry-run")));

        Path noExpiry = writeConfig(dir, 1, "\"expireTime\": \"${expireTimeMs}\"", "\"note\": \"no expiry\"",
                "\"LIMIT_GTC\"]", "\"LIMIT_GTC\", \"LIMIT_GTD\"]");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Main().run(args("--config", noExpiry.toString(), "--dry-run", "--types", "LIMIT_GTD")));
        assertTrue(e.getMessage().contains("expireTimeMs"), e.getMessage());

        Path noPreExec = writeConfig(dir, 1, "\"preExec\": \"${preExec}\"", "\"note\": \"no flag\"");
        e = assertThrows(IllegalArgumentException.class, () -> new Main().run(args("--config", noPreExec.toString(), "--dry-run")));
        assertTrue(e.getMessage().contains("preExec"), e.getMessage());
        assertEquals(0, new Main().run(args("--config", noPreExec.toString(), "--dry-run", "--pre-exec", "none")));
    }

    @Test
    void zeroSelectedOrdersIsAConfigErrorNotAPass(@TempDir Path dir) throws Exception {
        Path cfg = writeConfig(dir, 1, null, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new Main().run(
                args("--config", cfg.toString(), "--instruments", "BAD/SYM", "--types", "MARKET_IOC")));
        assertTrue(e.getMessage().contains("no orders"), e.getMessage());
    }

    @Test
    void expectKeysAreValidatedAndCaseInsensitive(@TempDir Path dir) throws Exception {
        Path lower = writeConfig(dir, 1, "\"expect\": { \"LIMIT_FOK\"", "\"expect\": { \"limit_fok\"");
        assertEquals(0, new Main().run(args("--config", lower.toString(), "--dry-run")));
        TesterConfig c = TestConfigs.localMock(1);
        c.expect.put("Default", List.of(OrderStatus.FILLED));
        c.expect.put("limit_ioc+preexec", List.of(OrderStatus.FILLED));
        Main.validateRun(c, 0);
        assertTrue(c.expect.containsKey("default"));
        assertTrue(c.expect.containsKey("LIMIT_FOK"));
        assertTrue(c.expect.containsKey("PREEXEC"));
        assertTrue(c.expect.containsKey("LIMIT_IOC+PREEXEC"));
        Path typo = writeConfig(dir, 1, "\"expect\": { \"LIMIT_FOK\"", "\"expect\": { \"LIMT_FOK\"");
        assertThrows(IllegalArgumentException.class, () -> new Main().run(args("--config", typo.toString(), "--dry-run")));
    }

    @Test
    void unsetCredentialsExitAsConfigErrorWithoutConnecting(@TempDir Path dir) throws Exception {
        try (MockWsServer mock = new MockWsServer(0, "key123", "sekret")) {
            Path cfg = writeConfig(dir, mock.port(), "\"apiKey\": \"key123\"", "\"apiKey\": \"${env:OT_DEFINITELY_NOT_SET}\"");
            assertThrows(IllegalArgumentException.class, () -> new Main().run(args("--config", cfg.toString())));
            assertEquals(0, mock.connections.get());
        }
    }

    @Test
    void capNoteListsWhatTheCapLeavesOut() throws Exception {
        TesterConfig c = TestConfigs.localMock(1);
        List<com.cypator.ordertester.model.OrderRequest> all = ScenarioGenerator.generate(c, "R", 0, java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of());
        assertNull(Main.capNote(all, all, 0));
        String note = Main.capNote(all, all.subList(0, 3), 3);
        assertTrue(note.contains("sides never sent: [SELL]"), note);
        assertTrue(note.contains("instruments never sent"), note);
    }

    @Test
    void argParsing() {
        assertThrows(IllegalArgumentException.class, () -> args("--config", "x", "--timeout", "0"));
        assertThrows(IllegalArgumentException.class, () -> args("--config", "x", "--repeat", "0"));
        assertThrows(IllegalArgumentException.class, () -> args("--config", "x", "--types", ""));
        assertThrows(IllegalArgumentException.class, () -> args("--config", "x", "--instruments", " , "));
        assertThrows(IllegalArgumentException.class, () -> args("--config", "x", "--sides", "LONG"));
        assertThrows(IllegalArgumentException.class, () -> args("--config", "x", "--pre-exec", "maybe"));
        assertThrows(IllegalArgumentException.class, () -> args());
        assertThrows(IllegalArgumentException.class, () -> args("--config", "x", "--types", "LIMIT_FOKK"));
        assertThrows(IllegalArgumentException.class, () -> args("--config", "x", "--max-orders", "-1"));
        assertThrows(IllegalArgumentException.class, () -> args("--config", "x", "--url", "ws://h"));
        Main.Args a = args("--config", "x", "--types", "limit_fok, market_ioc", "--sides", "buy",
                "--trade-url", "ws://t", "--market-url", "ws://m", "--pre-exec", "Only");
        assertEquals(2, a.types.size());
        assertTrue(a.sides.contains("BUY"));
        assertEquals("ws://t", a.tradeUrl);
        assertEquals("ws://m", a.marketUrl);
        assertEquals(ScenarioGenerator.PreExecFilter.ONLY, a.preExec);
        assertTrue(args("--help").help);
    }
}
