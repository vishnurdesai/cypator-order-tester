package com.cypator.ordertester.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatesTest {

    private static JsonNode json(String s) throws Exception {
        return Templates.MAPPER.readTree(s);
    }

    @Test
    void wholeValuePlaceholderKeepsJsonType() throws Exception {
        Map<String, Object> v = new HashMap<>();
        v.put("qty", new BigDecimal("0.00010000"));
        v.put("id", 88);
        v.put("sym", "XRP/USD");
        String out = Templates.renderToString(json("{\"q\":\"${qty}\",\"i\":\"${id}\",\"s\":\"${sym}\"}"), v, true);
        assertEquals("{\"q\":0.00010000,\"i\":88,\"s\":\"XRP/USD\"}", out);
    }

    @Test
    void nullValuesAreDroppedOrKept() throws Exception {
        Map<String, Object> v = new HashMap<>();
        v.put("price", null);
        assertEquals("{}", Templates.renderToString(json("{\"price\":\"${price}\"}"), v, true));
        assertEquals("{\"price\":null}", Templates.renderToString(json("{\"price\":\"${price}\"}"), v, false));
    }

    @Test
    void inlinePlaceholdersAreTextAndNumbersArePlain() throws Exception {
        Map<String, Object> v = new HashMap<>();
        v.put("a", "X");
        v.put("n", new BigDecimal("1E+3"));
        assertEquals("{\"k\":\"X-1000\"}", Templates.renderToString(json("{\"k\":\"${a}-${n}\"}"), v, true));
    }

    @Test
    void unknownVariableFailsLoudly() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Templates.render(json("{\"k\":\"${nope}\"}"), new HashMap<>(), true));
        assertTrue(e.getMessage().contains("${nope}"));
    }

    @Test
    void commentKeysAreSkippedAndArraysRendered() throws Exception {
        Map<String, Object> v = new HashMap<>();
        v.put("x", 1);
        // "_c" references an unknown variable but is a comment key, so it must be skipped, not fail.
        assertEquals("{\"a\":[1,\"n1\"]}",
                Templates.renderToString(json("{\"_c\":\"${y}\",\"a\":[\"${x}\",\"n${x}\"]}"), v, true));
    }

    @Test
    void jsonPointerReadsPlainNumbersAndMissing() throws Exception {
        JsonNode n = Templates.MAPPER.readTree("{\"arg\":{\"code\":0,\"qty\":400000.0,\"s\":\"FILLED\",\"z\":null}}");
        assertEquals("0", Templates.at(n, "/arg/code"));
        assertEquals("FILLED", Templates.at(n, "/arg/s"));
        assertNull(Templates.at(n, "/arg/z"));
        assertNull(Templates.at(n, "/arg/missing"));
        assertEquals(0, new BigDecimal(Templates.at(n, "/arg/qty")).compareTo(new BigDecimal("400000")));
    }

    @Test
    void requireRejectsUnsetEnvironmentVariables() {
        assertThrows(IllegalArgumentException.class, () -> Templates.require("${env:OT_DEFINITELY_NOT_SET}", "x"));
        assertThrows(IllegalArgumentException.class, () -> Templates.require(" ", "x"));
        assertEquals("ok", Templates.require("ok", "x"));
    }
}
