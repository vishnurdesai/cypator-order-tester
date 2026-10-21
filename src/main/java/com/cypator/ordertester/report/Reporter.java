package com.cypator.ordertester.report;

import com.cypator.ordertester.model.OrderRequest;
import com.cypator.ordertester.model.OrderResult;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Reporter {
    private Reporter() {
    }

    public static Path writeCsv(Path dir, String runId, String environment, List<OrderResult> results) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("run-" + runId + "-" + (environment == null ? "env" : environment) + ".csv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("seq,clOrdId,instrument,scenario,orderType,preExec,side,quantity,qtyCurrency,price,priceSource,"
                    + "bookBid,bookAsk,bookAgeMs,status,pass,filledQty,avgPrice,orderId,ackMs,totalMs,text,events");
            for (OrderResult r : results) {
                OrderRequest q = r.request;
                w.println(String.join(",",
                        String.valueOf(q.seq), csv(q.clOrdId), csv(q.instrument.symbol), q.label(), q.type.name(),
                        String.valueOf(q.preExec), q.side, q.quantity.toPlainString(), csv(q.currency),
                        q.price == null ? "" : q.price.toPlainString(), csv(q.priceSource),
                        q.book == null || q.book.bid == null ? "" : q.book.bid.toPlainString(),
                        q.book == null || q.book.ask == null ? "" : q.book.ask.toPlainString(),
                        q.book == null ? "" : ms(q.book.ageMs),
                        r.status.name(), r.passed ? "PASS" : "FAIL", r.filledQty.toPlainString(),
                        r.avgPrice == null ? "" : r.avgPrice.toPlainString(), csv(r.orderId),
                        ms(r.ackLatencyMs), ms(r.totalLatencyMs), csv(r.text),
                        csv(String.join(" || ", r.events))));
            }
        }
        return file;
    }

    public static void printTable(List<OrderResult> results) {
        String fmt = "%-4s %-31s %-11s %-18s %-4s %14s %12s %-15s %-12s %-4s %12s %7s  %s%n";
        System.out.println();
        System.out.println("=== Results ===");
        System.out.printf(fmt, "#", "clOrdId", "instrument", "scenario", "side", "qty", "price", "priceSource",
                "status", "pass", "filled", "ms", "text");
        for (OrderResult r : results) {
            OrderRequest q = r.request;
            System.out.printf(fmt, q.seq, q.clOrdId, q.instrument.symbol, q.label(), q.side,
                    q.quantity.toPlainString() + (q.termQuantity ? " " + q.currency : ""),
                    q.price == null ? "-" : q.price.toPlainString(), q.priceSource, r.status, r.passed ? "OK" : "FAIL",
                    r.filledQty.toPlainString(), ms(r.totalLatencyMs), shorten(r.text, 70));
        }
        Map<String, Integer> byStatus = new TreeMap<>();
        int pass = 0;
        for (OrderResult r : results) {
            byStatus.merge(r.status.name(), 1, Integer::sum);
            if (r.passed) pass++;
        }
        System.out.printf("%d orders, %d pass, %d fail  %s%n", results.size(), pass, results.size() - pass, byStatus);
    }

    /** -1 (never happened) prints as empty. */
    private static String ms(long v) {
        return v < 0 ? "" : String.valueOf(v);
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
