package com.cypator.ordertester.config;

import com.cypator.ordertester.util.Templates;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static TesterConfig load(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .enable(JsonParser.Feature.ALLOW_COMMENTS);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1); // UTF-8 BOM (Windows editors)
        JsonNode tree = mapper.readTree(text);
        if (tree == null || !tree.isObject()) throw new IllegalArgumentException("Config " + file + " is not a JSON object");
        stripComments(tree);
        List<String> missing = new ArrayList<>();
        Templates.resolveEnvInPlace(tree, missing);
        TesterConfig cfg = mapper.treeToValue(tree, TesterConfig.class);
        if (!missing.isEmpty()) {
            System.out.println("[config] environment variables not set (only an error if the config actually uses them): "
                    + String.join(", ", new TreeSet<>(missing)));
        }
        return cfg;
    }

    /** Remove every key starting with "_" at any depth - they are comments, in beans and maps alike. */
    static void stripComments(JsonNode node) {
        if (node.isObject()) {
            Iterator<String> it = ((ObjectNode) node).fieldNames();
            List<String> drop = new ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith("_")) drop.add(k);
            }
            ((ObjectNode) node).remove(drop);
            node.forEach(ConfigLoader::stripComments);
        } else if (node.isArray()) {
            node.forEach(ConfigLoader::stripComments);
        }
    }
}
