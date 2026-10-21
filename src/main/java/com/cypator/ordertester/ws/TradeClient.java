package com.cypator.ordertester.ws;

import com.cypator.ordertester.config.TesterConfig.TradeConfig;
import com.cypator.ordertester.config.TesterConfig.TradeResponse;
import com.cypator.ordertester.config.TesterConfig.WsConfig;
import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderResult;
import com.cypator.ordertester.model.OrderStatus;
import com.cypator.ordertester.util.Templates;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trade session: sends orders one at a time and blocks until each reaches a final state.
 *
 * Reply handling (per the WS Taker API):
 * <ul>
 *   <li>{"op":"order", ..., "code":0}  - the order echo = ACK; a non-zero code = REJECTED;</li>
 *   <li>{"op":"trade", "state": FILLED | PARTIALLY_FILLED | CANCELED | REJECTED} - execution report;</li>
 *   <li>{"op":"cancel_order", "code": !=0} - the cancel was refused (the order may still rest);</li>
 *   <li>errors without clOrderId (e.g. op=invalid) - credited to the single order in flight.</li>
 * </ul>
 * Safety: pings never overlap an order (see {@link WsSession#beginFlight()}), and after a TIMEOUT the
 * socket is replaced so that a late reply cannot be credited to the next order.
 */
public final class TradeClient implements AutoCloseable, WsSession.Handler {
    private final TradeConfig tc;
    private final TradeResponse rsp;
    private final WsSession session;

    /** Every clOrdId (order and cancel) sent in this run -> its order. Kept so late replies are recognised. */
    private final Map<String, PendingOrder> byClOrdId = new ConcurrentHashMap<>();
    private volatile PendingOrder current;

    public TradeClient(WsConfig ws) {
        if (ws == null) throw new IllegalArgumentException("Missing 'ws' section in config");
        this.tc = ws.trade;
        if (tc.orderTemplate == null || tc.orderTemplate.isNull()) {
            throw new IllegalArgumentException("Missing config: ws.trade.orderTemplate");
        }
        this.rsp = tc.response;
        this.session = new WsSession("trade", ws, tc, this);
    }

    public void validate() {
        session.validate();
        for (String v : rsp.stateMap.values()) {
            if (!List.of("ACK", "PARTIAL", "FILLED", "CANCELED", "REJECTED", "EXPIRED").contains(v.toUpperCase())) {
                throw new IllegalArgumentException("ws.trade.response.stateMap value '" + v
                        + "' must be ACK, PARTIAL, FILLED, CANCELED, REJECTED or EXPIRED");
            }
        }
    }

    public void connect() throws Exception {
        session.connect();
    }

    /** Reconnect (and re-logon) the trade socket if it is gone. True if it reconnected. */
    public boolean ensureConnected() throws Exception {
        return session.ensureConnected();
    }

    /**
     * Render every order (and cancel) message once without sending, so unknown ${vars} or unset ${env:} values
     * are a config error before any socket is opened. Book-derived prices are not known yet and render as absent.
     */
    public void checkTemplates(List<OrderRequest> orders) {
        for (OrderRequest o : orders) {
            try {
                Map<String, Object> v = o.templateVars(tc.tifMap);
                Templates.renderToString(tc.orderTemplate, v, true);
                if (o.type.isResting() && tc.cancelRestingOrders && tc.cancelTemplate != null && !tc.cancelTemplate.isNull()) {
                    v.put("origClOrdId", o.clOrdId);
                    v.put("cancelClOrdId", o.clOrdId + "-C");
                    v.put("orderId", "");
                    Templates.renderToString(tc.cancelTemplate, v, true);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("ws.trade order/cancel template for " + o.describe() + ": " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- orders

    /** Send one (already priced) order and wait up to timeoutMs for its final state. Never throws. */
    public OrderResult send(OrderRequest req, long timeoutMs) {
        PendingOrder po = new PendingOrder(req);
        boolean flying = false;
        try {
            String json = Templates.renderToString(tc.orderTemplate, req.templateVars(tc.tifMap), true);
            session.ensureConnected();
            session.beginFlight();
            flying = true;
            byClOrdId.put(req.clOrdId, po);
            current = po;
            po.markSent();
            session.send(json, true);
            po.awaitOutcome(timeoutMs);
            if (req.type.isResting() && !po.isDone()) cancelResting(po, timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            po.finish(OrderStatus.ERROR, "interrupted");
        } catch (Exception e) {
            po.finish(OrderStatus.ERROR, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            current = null;
            if (flying) session.endFlight();
            if (po.result.status == OrderStatus.TIMEOUT) session.recycle("order " + req.clOrdId + " timed out");
        }
        return po.result;
    }

    /** The order was acknowledged and rests on the book: cancel it (or report that it was left there). */
    private void cancelResting(PendingOrder po, long timeoutMs) throws Exception {
        OrderRequest req = po.request;
        if (!tc.cancelRestingOrders || tc.cancelTemplate == null || tc.cancelTemplate.isNull()) {
            po.finish(OrderStatus.RESTING, tc.cancelRestingOrders
                    ? "LEFT ON BOOK - no ws.trade.cancelTemplate configured, cancel it manually"
                    : "LEFT ON BOOK (cancelRestingOrders=false)");
            return;
        }
        session.ensureConnected(); // the socket may have dropped after the ACK; a fresh session can still cancel
        String cancelId = req.clOrdId + "-C";
        Map<String, Object> v = req.templateVars(tc.tifMap);
        v.put("origClOrdId", req.clOrdId);
        v.put("cancelClOrdId", cancelId);
        v.put("orderId", po.result.orderId);
        String json = Templates.renderToString(tc.cancelTemplate, v, true);
        byClOrdId.put(cancelId, po);
        po.markCancelSent();
        session.send(json, true);
        if (!po.awaitDone(timeoutMs)) po.timeout("cancel not confirmed within " + timeoutMs + " ms");
    }

    /** The order message as it would be sent (for --dry-run). */
    public String preview(OrderRequest req) {
        return Templates.renderToString(tc.orderTemplate, req.templateVars(tc.tifMap), true);
    }

    // ---------------------------------------------------------------- WsSession.Handler

    @Override
    public void onStaleMessage(JsonNode msg, String text) {
        String clOrdId = Templates.at(msg, rsp.clOrdIdPointer);
        PendingOrder old = clOrdId == null ? null : byClOrdId.get(clOrdId);
        if (old != null) old.event("LATE (old socket) " + text);
    }

    @Override
    public void onMessage(JsonNode msg, String text) {
        String op = session.op(msg);
        boolean codeOk = session.codeOk(msg);
        boolean errorOp = WsSession.containsIgnoreCase(rsp.errorOps, op);
        boolean isError = errorOp || !codeOk;
        boolean isAckOp = WsSession.containsIgnoreCase(rsp.ackOps, op);
        boolean isCancelReply = WsSession.containsIgnoreCase(rsp.cancelReplyOps, op);
        String errText = session.errorText(msg);

        String clOrdId = Templates.at(msg, rsp.clOrdIdPointer);
        boolean isExec = WsSession.containsIgnoreCase(rsp.executionOps, op) || isAckOp || isCancelReply || clOrdId != null;
        PendingOrder po;
        if (clOrdId != null) {
            po = byClOrdId.get(clOrdId);
            if (po == null) {
                session.log("   (clOrderId " + clOrdId + " is not from this run - ignored)");
                return;
            }
        } else if (isExec || isError) {
            po = current; // e.g. op=invalid carries no clOrderId: it belongs to the one order in flight
            if (po == null) return;
        } else {
            return;
        }
        if (po.isDone()) {
            po.event("LATE " + text);
            session.log("   (late message for " + po.request.clOrdId + ", already " + po.result.status + ")");
            return;
        }
        po.event(text);

        String orderId = Templates.at(msg, rsp.orderIdPointer);
        if (orderId != null) po.setOrderId(orderId);

        if (isCancelReply) {
            // Success is reported as a trade with state CANCELED; only a refusal needs handling here.
            if (isError) po.finish(OrderStatus.REJECTED, "cancel refused: " + errText);
            return;
        }
        if (isError && !isExec) {
            po.finish(OrderStatus.REJECTED, errText);
            return;
        }

        String state = Templates.at(msg, rsp.statePointer);
        String mapped = state == null ? null : mapState(state);
        if (mapped == null && isError) mapped = "REJECTED"; // error op / bad code with no usable state
        if (mapped == null && isAckOp) mapped = "ACK";       // order echo with code 0
        if (mapped == null) {
            session.log("   unmapped state '" + state + "' - add it to ws.trade.response.stateMap");
            return;
        }

        BigDecimal cum = dec(Templates.atAny(msg, rsp.cumQtyPointers));
        BigDecimal last = dec(Templates.atAny(msg, rsp.lastQtyPointers));
        BigDecimal px = dec(Templates.atAny(msg, rsp.lastPricePointers));
        BigDecimal avg = dec(Templates.atAny(msg, rsp.avgPricePointers));
        String reason = isError ? errText : state != null ? state : op;

        switch (mapped) {
            case "ACK":
                po.onAck(orderId);
                break;
            case "PARTIAL":
                if (cum != null || last != null) po.onFill(last, px, cum, avg);
                else po.onAck(orderId);
                break;
            case "FILLED":
                // A FILLED report without quantities means the whole order quantity.
                po.onFill(last, px, cum != null ? cum : (last == null ? po.request.quantity : null), avg);
                po.finish(OrderStatus.FILLED, reason);
                break;
            case "CANCELED":
                if ((cum != null && cum.signum() > 0) || (last != null && last.signum() > 0)) po.onFill(last, px, cum, avg);
                po.finish(OrderStatus.CANCELED, reason);
                break;
            case "REJECTED":
                po.finish(OrderStatus.REJECTED, reason);
                break;
            case "EXPIRED":
                po.finish(OrderStatus.EXPIRED, reason);
                break;
            default:
                session.log("   stateMap value '" + mapped + "' must be ACK, PARTIAL, FILLED, CANCELED, REJECTED or EXPIRED");
        }
    }

    @Override
    public void onLost(String why) {
        PendingOrder po = current;
        if (po == null) return;
        if (po.request.type.isResting() && po.sawAck() && !po.isCancelSent()) {
            return; // send() will reconnect and still try to cancel this resting order
        }
        po.finish(OrderStatus.ERROR, why);
    }

    /** Exact key first (so a configured override wins), then case-insensitive. */
    private String mapState(String state) {
        String exact = rsp.stateMap.get(state);
        if (exact != null) return exact.toUpperCase();
        for (Map.Entry<String, String> e : rsp.stateMap.entrySet()) {
            if (e.getKey().equalsIgnoreCase(state)) return e.getValue().toUpperCase();
        }
        return null;
    }

    private static BigDecimal dec(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void close() {
        session.close();
    }
}
