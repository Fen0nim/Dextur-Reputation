package org.dextur.reputation.application;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlFiles {
    private YamlFiles() { }

    public static Map<String, Object> read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return read(reader, path.getFileName().toString());
        }
    }
    public static Map<String, Object> resource(String resource) throws IOException {
        var stream = YamlFiles.class.getResourceAsStream(resource);
        if (stream == null) throw new IOException("Missing bundled resource: " + resource);
        try (Reader reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) { return read(reader, resource); }
    }
    private static Map<String, Object> read(Reader reader, String name) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(1_000_000);
        {
            Object value = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String))) {
                throw new IllegalArgumentException(name + ": expected a YAML mapping");
            }
            @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) map;
            return Map.copyOf(result);
        }
    }

    public static String text(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + ": non-empty string required");
        }
        return text;
    }

    public static int number(Map<String, Object> values, String key, int min, int max) {
        Object value = values.get(key);
        if (!(value instanceof Integer n) || n < min || n > max) {
            throw new IllegalArgumentException(key + ": integer required in [" + min + ", " + max + "]");
        }
        return n;
    }

    public static boolean flag(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof Boolean b)) throw new IllegalArgumentException(key + ": boolean required");
        return b;
    }

    public static void version(Map<String, Object> values) {
        number(values, "config-version", 1, 1);
    }
}
