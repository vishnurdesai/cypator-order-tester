package com.cypator.ordertester;

import com.cypator.ordertester.config.ConfigLoader;
import com.cypator.ordertester.config.TesterConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads config JSON through the real ConfigLoader (so "_" comments and env handling are exercised). */
public final class TestConfigs {
    private TestConfigs() {
    }

    public static TesterConfig fromJson(String json) throws Exception {
        Path f = Files.createTempFile("ot-config", ".json");
        try {
            Files.writeString(f, json);
            return ConfigLoader.load(f);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    /** The bundled local-mock.json, pointed at the given port. */
    public static TesterConfig localMock(int port) throws Exception {
        String json = Files.readString(Path.of("config", "local-mock.json"))
                .replace("ws://localhost:19877/", "ws://localhost:" + port + "/");
        return fromJson(json);
    }
}
