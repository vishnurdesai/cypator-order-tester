package com.cypator.ordertester.model;

import java.math.BigDecimal;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/** Outcome of one order, filled in by the WS client as responses arrive. */
public final class OrderResult {
    public final OrderRequest request;
    public OrderStatus status = OrderStatus.TIMEOUT;
    public String orderId = "";
    public BigDecimal filledQty = BigDecimal.ZERO;
    public BigDecimal avgPrice;
    public String text = "";
    public long ackLatencyMs = -1;
    public long totalLatencyMs = -1;
    /** Raw inbound messages for this order (late ones too, hence thread-safe), kept for the report. */
    public final List<String> events = new CopyOnWriteArrayList<>();
    /** Set by the runner after comparing with the expectations. */
    public boolean passed;

    public OrderResult(OrderRequest request) {
        this.request = request;
    }

    public OrderResult fail(OrderStatus status, String text) {
        this.status = status;
        this.text = text == null ? "" : text;
        return this;
    }
}
