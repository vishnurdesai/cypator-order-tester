package com.cypator.ordertester.model;

public enum OrderStatus {
    /** Fully filled. */
    FILLED,
    /** Partially filled, remainder canceled (typical IOC outcome). */
    PARTIAL_FILL,
    /** Canceled with no fill (IOC/FOK with no liquidity, or our own cancel of a resting order). */
    CANCELED,
    REJECTED,
    EXPIRED,
    /** GTC/GTD order acknowledged and deliberately left on the book (cancelRestingOrders=false or no cancelTemplate). */
    RESTING,
    /** No terminal answer within orderTimeoutMs. */
    TIMEOUT,
    /** Local/transport failure (could not connect, could not send, socket closed ...). */
    ERROR
}
