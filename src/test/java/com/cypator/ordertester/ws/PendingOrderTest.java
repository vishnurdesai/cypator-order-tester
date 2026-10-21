package com.cypator.ordertester.ws;

import com.cypator.ordertester.config.TesterConfig.Instrument;
import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderStatus;
import com.cypator.ordertester.model.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingOrderTest {

    private static PendingOrder order(OrderType type, String qty) {
        Instrument ins = new Instrument();
        ins.symbol = "USDC/USD";
        return new PendingOrder(new OrderRequest(1, ins, type, "BUY", new BigDecimal(qty), null, "USDC", false, "X-1", null));
    }

    @Test
    void iocPartialThenCancelIsPartialFillWithWeightedAverage() {
        PendingOrder po = order(OrderType.MARKET_IOC, "1000");
        po.onFill(new BigDecimal("300"), new BigDecimal("1.0"), new BigDecimal("300"), null);
        po.onFill(new BigDecimal("200"), new BigDecimal("2.0"), new BigDecimal("500"), null);
        po.finish(OrderStatus.CANCELED, "remainder canceled");
        assertEquals(OrderStatus.PARTIAL_FILL, po.result.status);
        assertEquals(0, po.result.filledQty.compareTo(new BigDecimal("500")));
        assertEquals(0, po.result.avgPrice.compareTo(new BigDecimal("1.4")));
    }

    @Test
    void cumQtyOnlyFillsDeriveTheIncrement() {
        PendingOrder po = order(OrderType.LIMIT_IOC, "100");
        po.onFill(null, new BigDecimal("2"), new BigDecimal("40"), null);
        po.onFill(null, new BigDecimal("4"), new BigDecimal("100"), null);
        po.finish(OrderStatus.FILLED, null);
        assertEquals(0, po.result.avgPrice.compareTo(new BigDecimal("3.2")));
    }

    @Test
    void filledWithoutQuantitiesMeansWholeOrder() {
        PendingOrder po = order(OrderType.LIMIT_FOK, "250");
        po.finish(OrderStatus.FILLED, "FILLED");
        assertEquals(0, po.result.filledQty.compareTo(new BigDecimal("250")));
    }

    @Test
    void firstFinalStateWins() {
        PendingOrder po = order(OrderType.LIMIT_FOK, "1");
        po.finish(OrderStatus.REJECTED, "first");
        po.finish(OrderStatus.FILLED, "second");
        assertEquals(OrderStatus.REJECTED, po.result.status);
        assertEquals("first", po.result.text);
    }

    @Test
    void restingAckReleasesTheWaiterWithoutFinishing() throws Exception {
        PendingOrder po = order(OrderType.LIMIT_GTC, "1");
        po.onAck("A1");
        po.awaitOutcome(1000);
        assertEquals(OrderStatus.RESTING, po.result.status);
        assertEquals("A1", po.result.orderId);
        assertTrue(!po.isDone());
    }

    @Test
    void replyRacingTheTimeoutCannotChangeTheResult() throws Exception {
        PendingOrder po = order(OrderType.LIMIT_GTC, "1");
        po.markSent();
        po.awaitOutcome(20);
        po.onAck("A1");
        po.onFill(new BigDecimal("1"), new BigDecimal("1"), null, null);
        assertEquals(OrderStatus.TIMEOUT, po.result.status);
        assertEquals(0, po.result.filledQty.signum());
        assertTrue(po.result.text.contains("ON THE BOOK"), po.result.text);
    }

    @Test
    void fillWithoutPriceDoesNotDiluteTheAverage() {
        PendingOrder po = order(OrderType.MARKET_IOC, "100");
        po.onFill(new BigDecimal("50"), new BigDecimal("2"), null, null);
        po.onFill(new BigDecimal("50"), null, null, null);
        po.finish(OrderStatus.FILLED, null);
        assertEquals(0, po.result.avgPrice.compareTo(new BigDecimal("2")));
        assertEquals(0, po.result.filledQty.compareTo(new BigDecimal("100")));
    }

    @Test
    void ackLatencyStaysUnsetWithoutAnyReply() {
        PendingOrder po = order(OrderType.LIMIT_FOK, "1");
        po.markSent();
        po.finish(OrderStatus.ERROR, "socket closed");
        assertEquals(-1, po.result.ackLatencyMs);
        PendingOrder po2 = order(OrderType.LIMIT_FOK, "1");
        po2.markSent();
        po2.event("{}");
        assertTrue(po2.result.ackLatencyMs >= 0);
    }

    @Test
    void restingOrderRejectedAfterAckWarnsButRejectWithoutAckDoesNot() {
        PendingOrder acked = order(OrderType.LIMIT_GTC, "1");
        acked.markSent();
        acked.onAck("A1");
        acked.finish(OrderStatus.REJECTED, "cancel refused");
        assertTrue(acked.result.text.contains("ON THE BOOK"));
        PendingOrder refused = order(OrderType.LIMIT_GTC, "1");
        refused.markSent();
        refused.finish(OrderStatus.REJECTED, "INVALID_TIF");
        assertEquals("INVALID_TIF", refused.result.text);
        PendingOrder canceled = order(OrderType.LIMIT_GTC, "1");
        canceled.onAck("A2");
        canceled.finish(OrderStatus.CANCELED, "CANCELED");
        assertEquals("CANCELED", canceled.result.text);
    }

    @Test
    void timeoutDescribesWhatWasSeen() throws Exception {
        PendingOrder po = order(OrderType.LIMIT_FOK, "1");
        po.awaitOutcome(50);
        assertEquals(OrderStatus.TIMEOUT, po.result.status);
        assertTrue(po.result.text.contains("never acked"));
    }
}
