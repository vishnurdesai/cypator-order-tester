package com.cypator.ordertester.config;

import com.cypator.ordertester.TestConfigs;
import com.cypator.ordertester.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static final String CFG = "{"
            + "\"environment\":\"local\","
            + "\"instruments\":[{\"symbol\":\"XRP/USD\",\"sizes\":[1],"
            + "  \"limitPrice\":{\"_note\":\"marketable\",\"BUY\":3.5,\"SELL\":1.5}}],"
            + "\"orderTypes\":[\"LIMIT_FOK\"],"
            + "\"expect\":{\"_why\":\"strict\",\"LIMIT_FOK\":[\"FILLED\"]},"
            + "\"ws\":{\"headers\":{\"_c\":\"not a header\",\"X-Test\":\"1\"},"
            + "  \"trade\":{\"url\":\"ws://x/trade\",\"orderTemplate\":{},\"tifMap\":{\"FOK\":\"FILL_OR_KILL\"},"
            + "    \"response\":{\"stateMap\":{\"DONE\":\"FILLED\"},\"cumQtyPointers\":\"/arg/cq\"}}}"
            + "}";

    @Test
    void commentKeysAreIgnoredInMapSectionsToo() throws Exception {
        TesterConfig cfg = TestConfigs.fromJson(CFG);
        assertEquals(2, cfg.instruments.get(0).limitPrice.size());
        assertEquals(0, cfg.instruments.get(0).limitPrice.get("BUY").compareTo(new BigDecimal("3.5")));
        assertEquals(1, cfg.expect.size());
        assertEquals(OrderStatus.FILLED, cfg.expect.get("LIMIT_FOK").get(0));
        assertFalse(cfg.ws.headers.containsKey("_c"));
        assertTrue(cfg.ws.headers.containsKey("X-Test"));
    }

    @Test
    void configuredStateMapIsMergedIntoDefaults() throws Exception {
        TesterConfig cfg = TestConfigs.fromJson(CFG);
        assertEquals("FILLED", cfg.ws.trade.response.stateMap.get("DONE"));
        assertEquals("PARTIAL", cfg.ws.trade.response.stateMap.get("PARTIALLY_FILLED"));
        assertEquals("ACK", cfg.ws.trade.response.stateMap.get("NEW"));
        assertEquals("FILL_OR_KILL", cfg.ws.trade.tifMap.get("FOK"));
        assertEquals("GOOD_TILL_CANCEL", cfg.ws.trade.tifMap.get("GTC"), "tifMap merges into defaults");
        assertEquals(java.util.List.of("/arg/cq"), cfg.ws.trade.response.cumQtyPointers, "single value accepted as list");
    }

    @Test
    void utf8BomIsAccepted(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("bom.json");
        Files.write(f, ("﻿" + CFG).getBytes(StandardCharsets.UTF_8));
        assertEquals("local", ConfigLoader.load(f).environment);
    }

    @Test
    void shippedConfigsBind() throws Exception {
        TesterConfig sample = ConfigLoader.load(Path.of("config", "rippleuat.sample.json"));
        assertEquals("rippleuat", sample.environment);
        assertTrue(sample.ws.trade.cancelTemplate.toString().contains("cancel_order"));
        assertTrue(sample.ws.market.subscribeTemplate.toString().contains("snapshot"));
        assertEquals(java.util.List.of(com.cypator.ordertester.model.OrderType.LIMIT_FOK), sample.preExecOrderTypes);
        TesterConfig mock = ConfigLoader.load(Path.of("config", "local-mock.json"));
        assertEquals(5, mock.orderTypes.size());
        assertTrue(mock.ws.market.enabled);
    }
}
