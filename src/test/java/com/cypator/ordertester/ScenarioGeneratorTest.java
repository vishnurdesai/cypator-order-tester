package com.cypator.ordertester;

import com.cypator.ordertester.config.TesterConfig;
import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioGeneratorTest {

    private static final String BASE = "{"
            + "\"environment\":\"local\","
            + "\"run\":{\"sides\":[\"BUY\",\"SELL\"]},"
            + "\"instruments\":["
            + "  {\"symbol\":\"XRP/USD\",\"sizes\":[10,250],\"limitPrice\":{\"BUY\":3.5,\"SELL\":1.5}},"
            + "  {\"symbol\":\"USDC/USD\",\"sizes\":[100],\"limitPrice\":{\"BUY\":1.01,\"SELL\":0.99},\"quantityIn\":[\"BASE\",\"TERM\"]}"
            + "],"
            + "\"orderTypes\":[\"MARKET_IOC\",\"LIMIT_FOK\",\"LIMIT_GTD\"],"
            + "\"ws\":{\"trade\":{\"url\":\"ws://x\",\"orderTemplate\":{}}}"
            + "}";

    private static List<OrderRequest> gen(TesterConfig cfg, int max) {
        return ScenarioGenerator.generate(cfg, "RUN", max, Set.of(), Set.of(), Set.of());
    }

    @Test
    void coversEveryTypeOnEveryInstrumentBeforeSecondSizeOrSide() throws Exception {
        List<OrderRequest> o = gen(TestConfigs.fromJson(BASE), 6);
        assertEquals(6, o.size());
        assertTrue(o.stream().allMatch(r -> r.side.equals("BUY")));
        assertEquals(Set.of("XRP/USD", "USDC/USD"), o.stream().map(r -> r.instrument.symbol).collect(Collectors.toSet()));
        assertEquals(Set.of(OrderType.MARKET_IOC, OrderType.LIMIT_FOK, OrderType.LIMIT_GTD),
                o.stream().map(r -> r.type).collect(Collectors.toSet()));
        assertTrue(o.stream().allMatch(r -> r.quantity.intValue() != 250), "second size must come later");
    }

    @Test
    void fullExpansionCountAndSequentialIds() throws Exception {
        // XRP: 2 sizes x 2 sides x 3 types = 12; USDC: 1 size x 2 sides x 2 ccy x 3 types = 12
        List<OrderRequest> o = gen(TestConfigs.fromJson(BASE), 0);
        assertEquals(24, o.size());
        for (int i = 0; i < o.size(); i++) {
            assertEquals(i + 1, o.get(i).seq);
            assertEquals(String.format("OT-WS-RUN-%03d", i + 1), o.get(i).clOrdId);
        }
        assertEquals(o.size(), o.stream().map(r -> r.clOrdId).distinct().count());
    }

    @Test
    void pricesTifCurrencyAndExpiry() throws Exception {
        List<OrderRequest> o = gen(TestConfigs.fromJson(BASE), 0);
        for (OrderRequest r : o) {
            if (r.type.needsLimitPrice()) assertNotNull(r.price);
            else assertNull(r.price);
            if (r.type == OrderType.LIMIT_GTD) {
                assertEquals(60, r.gtdExpireMinutes);
                long exp = (Long) r.templateVars(null).get("expireTimeMs");
                assertTrue(exp > System.currentTimeMillis() + 59 * 60_000L, "expiry is computed at render time");
            } else {
                assertNull(r.gtdExpireMinutes);
                assertNull(r.templateVars(null).get("expireTimeMs"));
            }
        }
        OrderRequest sellLimit = o.stream().filter(r -> r.side.equals("SELL") && r.type == OrderType.LIMIT_FOK
                && r.instrument.symbol.equals("XRP/USD")).findFirst().orElseThrow();
        assertEquals(0, sellLimit.price.compareTo(new java.math.BigDecimal("1.5")));
        assertTrue(o.stream().anyMatch(r -> r.termQuantity && r.currency.equals("USD")));
        assertTrue(o.stream().filter(r -> r.instrument.symbol.equals("XRP/USD")).noneMatch(r -> r.termQuantity));
    }

    @Test
    void filtersApply() throws Exception {
        List<OrderRequest> o = ScenarioGenerator.generate(TestConfigs.fromJson(BASE), "RUN", 0,
                Set.of(OrderType.LIMIT_FOK), Set.of("USDC/USD"), Set.of("SELL"));
        assertEquals(2, o.size()); // base + term
        assertTrue(o.stream().allMatch(r -> r.type == OrderType.LIMIT_FOK && r.side.equals("SELL")));
    }

    @Test
    void perInstrumentOrderTypesOverride() throws Exception {
        String json = BASE.replace("\"quantityIn\":[\"BASE\",\"TERM\"]", "\"orderTypes\":[\"MARKET_FOK\"]");
        List<OrderRequest> o = gen(TestConfigs.fromJson(json), 0);
        assertTrue(o.stream().filter(r -> r.instrument.symbol.equals("USDC/USD")).allMatch(r -> r.type == OrderType.MARKET_FOK));
        assertFalse(o.stream().filter(r -> r.instrument.symbol.equals("XRP/USD")).anyMatch(r -> r.type == OrderType.MARKET_FOK));
    }

    @Test
    void missingLimitPriceIsAConfigError() throws Exception {
        String json = BASE.replace("\"limitPrice\":{\"BUY\":3.5,\"SELL\":1.5}", "\"limitPrice\":{\"BUY\":3.5}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> gen(TestConfigs.fromJson(json), 0));
        assertTrue(e.getMessage().contains("limitPrice.SELL"));
    }

    @Test
    void tooLongClOrdIdPrefixIsAConfigError() throws Exception {
        String json = BASE.replace("\"run\":{", "\"run\":{\"clOrdIdPrefix\":\"" + "P".repeat(60) + "\",");
        assertThrows(IllegalArgumentException.class, () -> gen(TestConfigs.fromJson(json), 1));
    }

    @Test
    void invalidSideAndEmptySelectionsAreConfigErrors() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> gen(TestConfigs.fromJson(BASE.replace("\"SELL\"]", "\"SEL\"]")), 0));
        assertThrows(IllegalArgumentException.class, () -> ScenarioGenerator.generate(TestConfigs.fromJson(BASE),
                "RUN", 0, Set.of(), Set.of("NOPE/X"), Set.of()));
    }

    @Test
    void unknownConfigKeyIsRejectedButCommentsAreFine() throws Exception {
        assertThrows(Exception.class, () -> TestConfigs.fromJson(BASE.replace("\"environment\"", "\"enviroment\"")));
        TestConfigs.fromJson(BASE.replace("\"environment\"", "\"_note\":\"hi\",\"environment\""));
    }

    @Test
    void preExecScenariosAreAddedAfterTheNormalTypes() throws Exception {
        String json = BASE.replace("\"orderTypes\":[\"MARKET_IOC\",\"LIMIT_FOK\",\"LIMIT_GTD\"],",
                "\"orderTypes\":[\"MARKET_IOC\",\"LIMIT_FOK\"],\"preExecOrderTypes\":[\"LIMIT_FOK\"],");
        TesterConfig cfg = TestConfigs.fromJson(json);
        List<OrderRequest> o = gen(cfg, 3);
        assertEquals(List.of("MARKET_IOC", "LIMIT_FOK", "LIMIT_FOK+PREEXEC"),
                o.stream().map(OrderRequest::label).collect(Collectors.toList()));
        assertTrue(o.get(2).preExec);
        assertEquals(Boolean.TRUE, o.get(2).templateVars(null).get("preExec"));
        assertEquals(Boolean.FALSE, o.get(1).templateVars(null).get("preExec"));

        List<OrderRequest> only = ScenarioGenerator.generate(cfg, "RUN", 0, Set.of(), Set.of(), Set.of(),
                ScenarioGenerator.PreExecFilter.ONLY);
        assertTrue(!only.isEmpty() && only.stream().allMatch(r -> r.preExec));
        List<OrderRequest> none = ScenarioGenerator.generate(cfg, "RUN", 0, Set.of(), Set.of(), Set.of(),
                ScenarioGenerator.PreExecFilter.NONE);
        assertTrue(!none.isEmpty() && none.stream().noneMatch(r -> r.preExec));
        assertEquals(only.size() + none.size(), gen(cfg, 0).size());
    }

    @Test
    void preExecPriceSourceAndRestingTypesAreValidated() throws Exception {
        String base = BASE.replace("\"orderTypes\":[\"MARKET_IOC\",\"LIMIT_FOK\",\"LIMIT_GTD\"],",
                "\"orderTypes\":[\"LIMIT_FOK\"],\"preExecOrderTypes\":[\"LIMIT_FOK\",\"MARKET_IOC\"],");
        List<OrderRequest> o = ScenarioGenerator.generate(TestConfigs.fromJson(base), "RUN", 0, Set.of(), Set.of(), Set.of(),
                ScenarioGenerator.PreExecFilter.ONLY);
        assertTrue(o.stream().allMatch(r -> "pending-book".equals(r.priceSource)), "pre-exec never takes limitPrice");

        String fixed = base.replace("\"limitPrice\":{\"BUY\":3.5,\"SELL\":1.5}",
                "\"limitPrice\":{\"BUY\":3.5,\"SELL\":1.5},\"preExecPrice\":{\"BUY\":2.41,\"SELL\":2.40}");
        OrderRequest xrp = ScenarioGenerator.generate(TestConfigs.fromJson(fixed), "RUN", 0, Set.of(), Set.of("XRP/USD"),
                Set.of("BUY"), ScenarioGenerator.PreExecFilter.ONLY).get(0);
        assertEquals("config", xrp.priceSource);
        assertEquals(0, xrp.price.compareTo(new java.math.BigDecimal("2.41")));

        String noMd = base.replace("\"ws\":{\"trade\"", "\"ws\":{\"market\":{\"enabled\":false},\"trade\"");
        assertThrows(IllegalArgumentException.class, () -> gen(TestConfigs.fromJson(noMd), 0), "pre-exec needs a price source");

        String resting = base.replace("\"preExecOrderTypes\":[\"LIMIT_FOK\",\"MARKET_IOC\"]", "\"preExecOrderTypes\":[\"LIMIT_GTC\"]");
        assertThrows(IllegalArgumentException.class, () -> gen(TestConfigs.fromJson(resting), 0));
    }

    @Test
    void bookModeNeedsNoLimitPriceAndDefersPricing() throws Exception {
        String json = BASE.replace("\"limitPrice\":{\"BUY\":3.5,\"SELL\":1.5}", "\"priceMode\":\"book\"");
        List<OrderRequest> o = gen(TestConfigs.fromJson(json), 0);
        OrderRequest xrpLimit = o.stream().filter(r -> r.instrument.symbol.equals("XRP/USD") && r.type == OrderType.LIMIT_FOK)
                .findFirst().orElseThrow();
        assertNull(xrpLimit.price);
        assertEquals("pending-book", xrpLimit.priceSource);
        OrderRequest usdcLimit = o.stream().filter(r -> r.instrument.symbol.equals("USDC/USD") && r.type == OrderType.LIMIT_FOK)
                .findFirst().orElseThrow();
        assertEquals("config", usdcLimit.priceSource);
        assertThrows(IllegalArgumentException.class,
                () -> gen(TestConfigs.fromJson(BASE.replace("\"run\":{", "\"run\":{\"priceMode\":\"live\",")), 0));
    }

    @Test
    void orderVarsAndInstrumentVarsReachTheTemplate() throws Exception {
        String json = BASE.replace("\"orderTypes\":[", "\"orderVars\":{\"account\":\"ACC1\",\"baseChain\":null},\"orderTypes\":[")
                .replace("\"sizes\":[100]", "\"sizes\":[100],\"vars\":{\"account\":\"ACC2\"}");
        List<OrderRequest> o = gen(TestConfigs.fromJson(json), 0);
        OrderRequest xrp = o.stream().filter(r -> r.instrument.symbol.equals("XRP/USD")).findFirst().orElseThrow();
        OrderRequest usdc = o.stream().filter(r -> r.instrument.symbol.equals("USDC/USD")).findFirst().orElseThrow();
        assertEquals("ACC1", xrp.templateVars(null).get("account"));
        assertEquals("ACC2", usdc.templateVars(null).get("account"));
        assertTrue(xrp.templateVars(null).containsKey("baseChain"));
        assertNull(xrp.templateVars(null).get("baseChain"));
    }
}
