package com.cypator.ordertester.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ${var} substitution for JSON templates and strings.
 *
 * A string value that is exactly "${var}" is replaced by the variable with its own JSON type
 * (number stays a number, null stays null); placeholders inside a longer string are replaced
 * as text. ${env:NAME} reads an environment variable.
 */
public final class Templates {
    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");
    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);
    /** Exact decimals both ways: replies are read as BigDecimal (2.4100 keeps its scale), written in plain notation. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private Templates() {
    }

    /** Sentinel used internally to mark fields that should be removed. */
    private static final Object DROP = new Object();

    public static JsonNode render(JsonNode template, Map<String, Object> vars, boolean dropNulls) {
        if (template == null) return null;
        JsonNode out = renderNode(template, vars, dropNulls);
        return out == null ? NF.nullNode() : out;
    }

    public static String renderToString(JsonNode template, Map<String, Object> vars, boolean dropNulls) {
        try {
            return MAPPER.writeValueAsString(render(template, vars, dropNulls));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise template: " + e.getMessage(), e);
        }
    }

    /** Returns null when the node should be dropped from its parent. */
    private static JsonNode renderNode(JsonNode node, Map<String, Object> vars, boolean dropNulls) {
        if (node.isObject()) {
            ObjectNode out = NF.objectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().startsWith("_")) continue; // comment keys
                JsonNode v = renderNode(e.getValue(), vars, dropNulls);
                if (v != null) out.set(e.getKey(), v);
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = NF.arrayNode();
            for (JsonNode child : node) {
                JsonNode v = renderNode(child, vars, dropNulls);
                if (v != null) out.add(v);
            }
            return out;
        }
        if (node.isTextual()) {
            String s = node.textValue();
            Matcher whole = VAR.matcher(s);
            if (whole.matches()) {
                Object value = lookup(whole.group(1), vars);
                if (value == null) return dropNulls ? null : NF.nullNode();
                return toNode(value);
            }
            return TextNode.valueOf(renderString(s, vars));
        }
        return node.deepCopy();
    }

    public static String renderString(String s, Map<String, Object> vars) {
        if (s == null) return null;
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object value = lookup(m.group(1), vars);
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : asText(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Object lookup(String name, Map<String, Object> vars) {
        if (name.startsWith("env:")) {
            String v = System.getenv(name.substring(4));
            if (v == null) throw new IllegalArgumentException("Environment variable not set: " + name.substring(4));
            return v;
        }
        if (!vars.containsKey(name)) {
            throw new IllegalArgumentException("Unknown template variable ${" + name + "}. Known: " + vars.keySet());
        }
        return vars.get(name);
    }

    public static String asText(Object value) {
        if (value instanceof BigDecimal) return ((BigDecimal) value).toPlainString();
        if (value instanceof JsonNode) return ((JsonNode) value).isTextual() ? ((JsonNode) value).textValue() : value.toString();
        return String.valueOf(value);
    }

    private static JsonNode toNode(Object value) {
        if (value instanceof JsonNode) return (JsonNode) value;
        if (value instanceof BigDecimal) return NF.numberNode((BigDecimal) value);
        if (value instanceof Integer) return NF.numberNode((Integer) value);
        if (value instanceof Long) return NF.numberNode((Long) value);
        if (value instanceof Double) return NF.numberNode((Double) value);
        if (value instanceof Boolean) return NF.booleanNode((Boolean) value);
        return MAPPER.valueToTree(value);
    }

    /** Replace ${env:NAME} in every string of the tree. Unset variables are left as-is (checked when used). */
    public static void resolveEnvInPlace(JsonNode node, List<String> missing) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> keys = new ArrayList<>();
            obj.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                if (k.startsWith("_")) continue; // comment keys
                JsonNode child = obj.get(k);
                if (child.isTextual()) obj.set(k, TextNode.valueOf(resolveEnv(child.textValue(), missing)));
                else resolveEnvInPlace(child, missing);
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child.isTextual()) arr.set(i, TextNode.valueOf(resolveEnv(child.textValue(), missing)));
                else resolveEnvInPlace(child, missing);
            }
        }
    }

    private static String resolveEnv(String s, List<String> missing) {
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement = m.group(0);
            if (name.startsWith("env:")) {
                String v = System.getenv(name.substring(4));
                if (v != null) replacement = v;
                else missing.add(name.substring(4));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Fail fast if a required setting is empty or still points at an unset environment variable. */
    public static String require(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing config: " + what);
        if (value.contains("${env:")) throw new IllegalArgumentException("Config " + what + " uses an unset environment variable: " + value);
        return value;
    }

    /** First non-null value of several pointers (APIs that name a field two ways). */
    public static String atAny(JsonNode root, java.util.List<String> pointers) {
        if (pointers == null) return null;
        for (String p : pointers) {
            String v = at(root, p);
            if (v != null) return v;
        }
        return null;
    }

    /** Text at a JSON pointer, or null when absent / null. Numbers are returned in plain notation. */
    public static String at(JsonNode root, String pointer) {
        if (root == null || pointer == null || pointer.isEmpty()) return null;
        JsonNode n = root.at(pointer);
        if (n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.decimalValue().toPlainString();
        if (n.isValueNode()) return n.asText();
        return n.toString();
    }
}
