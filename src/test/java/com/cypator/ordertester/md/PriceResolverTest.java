package com.cypator.ordertester.md;

import com.cypator.ordertester.config.TesterConfig;
import com.cypator.ordertester.config.TesterConfig.Instrument;
import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceResolverTest {
    private static final BookView BOOK = new BookView("XRP/USD", new BigDecimal("2.40"), BigDecimal.TEN,
            new BigDecimal("2.41"), BigDecimal.TEN, 2, 2, 5, 10, 2, null);
    private static final BookView NO_ASK = new BookView("XRP/USD", new BigDecimal("2.40"), BigDecimal.TEN,
            null, null, 1, 0, 5, 10, 2, null);

    private static TesterConfig cfg(String mode) {
        TesterConfig c = new TesterConfig();
        c.run.priceMode = mode;
        c.run.aggressiveBps = 20;
        c.run.passiveBps = 100;
        c.run.preExecBps = 0;
        return c;
    }

    private static OrderRequest order(OrderType type, boolean preExec, String side, Integer decimals) {
        Instrument ins = new Instrument();
        ins.symbol = "XRP/USD";
        ins.priceDecimals = decimals;
        ins.limitPrice = Map.of("BUY", new BigDecimal("3.5"), "SELL", new BigDecimal("1.5"));
        return new OrderRequest(1, ins, type, preExec, side, BigDecimal.ONE, null, "pending-book", null,
                "XRP", false, "X", null, null);
    }

    private static BigDecimal px(TesterConfig c, OrderType t, boolean pre, String side, BookView b) {
        return new PriceResolver(c).resolve(order(t, pre, side, null), b).price;
    }

    @Test
    void aggressiveCrossesTheTouchAndRoundsAwayFromIt() {
        TesterConfig c = cfg("book");
        assertEquals(new BigDecimal("2.42"), px(c, OrderType.LIMIT_FOK, false, "BUY", BOOK));  // 2.41 * 1.002 = 2.41482 -> up
        assertEquals(new BigDecimal("2.39"), px(c, OrderType.LIMIT_IOC, false, "SELL", BOOK)); // 2.40 * 0.998 = 2.3952 -> down
    }

    @Test
    void restingOrdersArePassiveSoTheyRest() {
        TesterConfig c = cfg("book");
        assertEquals(new BigDecimal("2.37"), px(c, OrderType.LIMIT_GTC, false, "BUY", BOOK));  // bid 2.40 * 0.99 = 2.376 -> down
        assertEquals(new BigDecimal("2.44"), px(c, OrderType.LIMIT_GTC, false, "SELL", BOOK)); // ask 2.41 * 1.01 = 2.4341 -> up
    }

    @Test
    void preExecIsPricedAtTheTouch() {
        TesterConfig c = cfg("book");
        assertEquals(new BigDecimal("2.41"), px(c, OrderType.LIMIT_FOK, true, "BUY", BOOK));
        assertEquals(new BigDecimal("2.40"), px(c, OrderType.LIMIT_FOK, true, "SELL", BOOK));
        c.run.preExecBps = 10;
        assertEquals(new BigDecimal("2.42"), px(c, OrderType.LIMIT_FOK, true, "BUY", BOOK)); // 2.41241 -> up
    }

    @Test
    void marketOrdersCarryTheTouchByDefault() {
        TesterConfig c = cfg("book");
        assertEquals(new BigDecimal("2.41"), px(c, OrderType.MARKET_IOC, false, "BUY", BOOK), "API marks price as required");
        assertEquals(new BigDecimal("3.5"), px(cfg("config"), OrderType.MARKET_IOC, false, "BUY", null), "no book -> limitPrice");
        c.run.sendPriceOnMarket = false;
        assertNull(px(c, OrderType.MARKET_IOC, false, "BUY", BOOK));
    }

    @Test
    void preExecNeverUsesTheOffMarketLimitPrice() {
        TesterConfig c = cfg("config");
        // limitPrice BUY is 3.5 (deliberately marketable) - pre-exec must use the touch instead
        assertEquals(new BigDecimal("2.41"), px(c, OrderType.LIMIT_FOK, true, "BUY", BOOK));
        assertEquals(new BigDecimal("2.41"), px(c, OrderType.MARKET_IOC, true, "BUY", BOOK), "MARKET pre-exec is priced too");
        assertThrows(IllegalStateException.class, () -> px(c, OrderType.LIMIT_FOK, true, "BUY", null), "no book, no preExecPrice");
    }

    @Test
    void fixedPreExecPriceFarFromTheTouchIsRefused() {
        TesterConfig c = cfg("config");
        Instrument ins = new Instrument();
        ins.symbol = "XRP/USD";
        OrderRequest far = new OrderRequest(1, ins, OrderType.LIMIT_FOK, true, "BUY", BigDecimal.ONE, new BigDecimal("3.5"),
                "config", null, "XRP", false, "X", null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new PriceResolver(c).resolve(far, BOOK));
        assertTrue(e.getMessage().contains("bps from the touch"), e.getMessage());
        OrderRequest near = far.withPricing(new BigDecimal("2.42"), "config", null);
        assertEquals(new BigDecimal("2.42"), new PriceResolver(c).resolve(near, BOOK).price);
        c.run.preExecMaxDeviationBps = 0;
        assertEquals(new BigDecimal("3.5"), new PriceResolver(c).resolve(far, BOOK).price, "0 disables the check");
    }

    @Test
    void configModeUsesLimitPriceAndIgnoresTheBook() {
        TesterConfig c = cfg("config");
        assertEquals(new BigDecimal("3.5"), px(c, OrderType.LIMIT_FOK, false, "BUY", BOOK));
        assertEquals(new BigDecimal("1.5"), px(c, OrderType.LIMIT_IOC, false, "SELL", null));
    }

    @Test
    void priceDecimalsOverrideAndMissingSide() {
        TesterConfig c = cfg("book");
        OrderRequest o = order(OrderType.LIMIT_FOK, false, "BUY", 4);
        assertEquals(new BigDecimal("2.4149"), new PriceResolver(c).resolve(o, BOOK).price);
        assertThrows(IllegalStateException.class, () -> px(c, OrderType.LIMIT_FOK, false, "BUY", NO_ASK));
        assertThrows(IllegalStateException.class, () -> px(c, OrderType.LIMIT_FOK, false, "BUY", null));
        // a one-sided book still prices a passive BUY from the bid
        assertEquals(new BigDecimal("2.37"), px(c, OrderType.LIMIT_GTC, false, "BUY", NO_ASK));
    }

    @Test
    void resolvedOrderRemembersSourceAndBook() {
        OrderRequest r = new PriceResolver(cfg("book")).resolve(order(OrderType.LIMIT_GTC, false, "SELL", null), BOOK);
        assertEquals("book-passive", r.priceSource);
        assertEquals(BOOK, r.book);
    }
}
