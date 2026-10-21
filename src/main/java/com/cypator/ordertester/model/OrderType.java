package com.cypator.ordertester.model;

/**
 * Order flavours the WS taker API accepts: MARKET / LIMIT x IOC / FOK, plus GTC / GTD when the
 * acceptor has GTC_TIF / GTD_TIF enabled (they are off on rippleuat lcaripplea, for example).
 */
public enum OrderType {
    MARKET_IOC(Kind.MARKET, "IOC"),
    MARKET_FOK(Kind.MARKET, "FOK"),
    LIMIT_IOC(Kind.LIMIT, "IOC"),
    LIMIT_FOK(Kind.LIMIT, "FOK"),
    LIMIT_GTC(Kind.LIMIT, "GTC"),
    LIMIT_GTD(Kind.LIMIT, "GTD");

    public enum Kind { MARKET, LIMIT }

    public final Kind kind;
    public final String tif;

    OrderType(Kind kind, String tif) {
        this.kind = kind;
        this.tif = tif;
    }

    public boolean needsLimitPrice() {
        return kind == Kind.LIMIT;
    }

    /** GTC/GTD orders rest on the book after the ACK instead of ending in a fill or a kill. */
    public boolean isResting() {
        return "GTC".equals(tif) || "GTD".equals(tif);
    }

    /** MARKET / LIMIT, as the WS API and the DB enum name it. */
    public String orderTypeName() {
        return kind.name();
    }
}
