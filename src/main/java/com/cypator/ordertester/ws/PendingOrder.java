package com.cypator.ordertester.ws;

import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderResult;
import com.cypator.ordertester.model.OrderStatus;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * State of the in-flight order: collects ACK / fills / cancels from the socket thread and wakes
 * the sender when the order reaches a final state (or, for GTC/GTD, when it is acknowledged).
 *
 * All mutators are synchronized and ignore updates once the order is final, so a reply that races
 * the timeout cannot change a result the sender has already returned.
 */
public final class PendingOrder {
    /** Final states after which a GTC/GTD order is known to be off the book (or deliberately left there). */
    private static final Set<OrderStatus> OFF_BOOK = EnumSet.of(OrderStatus.FILLED, OrderStatus.PARTIAL_FILL,
            OrderStatus.CANCELED, OrderStatus.EXPIRED, OrderStatus.RESTING);

    public final OrderRequest request;
    public final OrderResult result;
    private long sentNanos = System.nanoTime();
    private final CountDownLatch done = new CountDownLatch(1);
    private final CountDownLatch acked = new CountDownLatch(1);
    private BigDecimal notional = BigDecimal.ZERO;   // sum(fillQty * fillPx) over fills that had a price
    private BigDecimal pricedQty = BigDecimal.ZERO;  // sum(fillQty) over fills that had a price
    private boolean sent;
    private boolean sawAck;
    private boolean cancelSent;

    public PendingOrder(OrderRequest request) {
        this.request = request;
        this.result = new OrderResult(request);
    }

    /** Start the latency clock; call right before the order goes on the wire. */
    public synchronized void markSent() {
        sent = true;
        sentNanos = System.nanoTime();
    }

    public synchronized void markCancelSent() {
        cancelSent = true;
    }

    public synchronized boolean isCancelSent() {
        return cancelSent;
    }

    /** True once the API acknowledged (or filled) the order. */
    public synchronized boolean sawAck() {
        return sawAck;
    }

    /** Record an inbound message for this order; the first one sets the ACK latency. */
    public synchronized void event(String raw) {
        result.events.add(raw);
        if (!isDone() && result.ackLatencyMs < 0) result.ackLatencyMs = elapsedMs();
    }

    public synchronized void setOrderId(String orderId) {
        if (orderId != null && !orderId.isEmpty()) result.orderId = orderId;
    }

    public synchronized void onAck(String orderId) {
        if (isDone()) return;
        sawAck = true;
        setOrderId(orderId);
        if (request.type.isResting() && result.filledQty.signum() == 0) result.status = OrderStatus.RESTING;
        acked.countDown();
    }

    /**
     * Record a fill. cumQty may be null (computed from lastQty); lastQty may be null (derived from cumQty).
     * Fills without a price count towards the filled quantity but not towards the average price.
     */
    public synchronized void onFill(BigDecimal lastQty, BigDecimal lastPx, BigDecimal cumQty, BigDecimal avgPx) {
        if (isDone()) return;
        sawAck = true;
        BigDecimal prevCum = result.filledQty;
        BigDecimal newCum = cumQty != null ? cumQty : prevCum.add(lastQty == null ? BigDecimal.ZERO : lastQty);
        BigDecimal fillQty = lastQty != null ? lastQty : newCum.subtract(prevCum);
        if (lastPx != null && fillQty.signum() > 0) {
            notional = notional.add(fillQty.multiply(lastPx));
            pricedQty = pricedQty.add(fillQty);
        }
        if (newCum.compareTo(prevCum) > 0) result.filledQty = newCum;
        if (avgPx != null && avgPx.signum() > 0) {
            result.avgPrice = avgPx;
        } else if (pricedQty.signum() > 0) {
            result.avgPrice = notional.divide(pricedQty, MathContext.DECIMAL64);
        }
        acked.countDown();
    }

    /** Final state. Ignored if the order is already final. CANCELED after a fill becomes PARTIAL_FILL. */
    public synchronized void finish(OrderStatus status, String text) {
        if (isDone()) return;
        if (status == OrderStatus.CANCELED && result.filledQty.signum() > 0) status = OrderStatus.PARTIAL_FILL;
        if (status == OrderStatus.FILLED && result.filledQty.signum() == 0) result.filledQty = request.quantity;
        result.status = status;
        if (text != null && !text.isEmpty()) result.text = text;
        if (mayBeOnBook(status)) result.text += onBookWarning();
        result.totalLatencyMs = elapsedMs();
        acked.countDown();
        done.countDown();
    }

    /** Mark a still-open order as timed out, keeping what was learnt so far in the text. */
    public synchronized void timeout(String text) {
        if (isDone()) return;
        result.text = text + (sawAck
                ? " (acked, filled " + result.filledQty.toPlainString() + ")"
                : " (never acked)");
        if (mayBeOnBook(OrderStatus.TIMEOUT)) result.text += onBookWarning();
        result.status = OrderStatus.TIMEOUT;
        result.totalLatencyMs = elapsedMs();
        acked.countDown();
        done.countDown();
    }

    /**
     * A GTC/GTD order may still rest on the book when it ends in a non-final-on-book state after an ACK,
     * or after it was sent and then the outcome became unknown (timeout, socket error).
     * A REJECTED without an ACK means the API refused it.
     */
    private boolean mayBeOnBook(OrderStatus status) {
        if (!request.type.isResting() || OFF_BOOK.contains(status)) return false;
        return sawAck || (sent && (status == OrderStatus.TIMEOUT || status == OrderStatus.ERROR));
    }

    private String onBookWarning() {
        return " | WARNING: " + request.type + " order " + request.clOrdId
                + (result.orderId.isEmpty() ? "" : " / " + result.orderId)
                + (sawAck ? " was ACKED" : " may have been accepted") + " and may still be ON THE BOOK - check and cancel it manually";
    }

    public boolean isDone() {
        return done.getCount() == 0;
    }

    public boolean awaitDone(long timeoutMs) throws InterruptedException {
        return done.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Wait for the final state, or only for the ACK when this is a resting (GTC/GTD) order. */
    public void awaitOutcome(long timeoutMs) throws InterruptedException {
        if (request.type.isResting()) {
            if (!acked.await(timeoutMs, TimeUnit.MILLISECONDS)) timeout("no ACK within " + timeoutMs + " ms");
            return;
        }
        if (!awaitDone(timeoutMs)) timeout("no final state within " + timeoutMs + " ms");
    }

    private long elapsedMs() {
        return (System.nanoTime() - sentNanos) / 1_000_000;
    }
}
