package org.dextur.reputation.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.dextur.reputation.domain.PlayerLanguage;

import static org.dextur.reputation.application.YamlFiles.*;

public record Settings(String scope, PlayerLanguage defaultLanguage, int cacheSize, int cacheSeconds,
        int workers, int queueSize, int shutdownSeconds, boolean placeholderApi,
        Database database, Storage storage) {
    /** Source compatibility for Phase 1 internal callers; public Reputation API is unchanged. */
    public Settings(String scope, PlayerLanguage language, int size, int ttl, int workers, int queue,
            int shutdown, boolean papi, Database database) {
        this(scope, language, size, ttl, workers, queue, shutdown, papi, database,
                new Storage(StorageType.MYSQL, "storage/json", 67108864, "storage/reputation.sqlite", 5000));
    }
    public record Storage(StorageType type, String jsonDirectory, int maximumJsonBytes,
            String sqliteFile, int busyTimeoutMs) { }
    public record Database(String host, int port, String schema, String username,
            String passwordEnvironment, String sslMode, int poolSize, int connectionTimeoutMs,
            int maxLifetimeMs) { }

    public static Settings load(Path directory) throws IOException {
        return load(directory, directory.resolve("storage.yml"));
    }

    public static Settings load(Path directory, Path storageFile) throws IOException {
        Map<String, Object> c = read(directory.resolve("config.yml"));
        Map<String, Object> document = read(storageFile);
        Map<String, Object> i = read(directory.resolve("integrations.yml"));
        version(c); version(i);
        int version = number(document, "config-version", 1, 2);
        Map<String, Object> storage = section(document, "storage", version == 1);
        StorageType type;
        try { type = StorageType.valueOf(storage.containsKey("type") ? text(storage, "type") : version == 1 ? "MYSQL" : ""); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("storage.type: JSON, SQLITE or MYSQL required"); }
        Map<String, Object> d = version == 1 ? document : section(storage, "mysql", false);
        Map<String, Object> j = section(storage, "json", true);
        Map<String, Object> s = section(storage, "sqlite", true);
        String json = relativePath(j.getOrDefault("directory", "storage/json"), "storage.json.directory");
        String sqlite = relativePath(s.getOrDefault("file", "storage/reputation.sqlite"), "storage.sqlite.file");
        int maximum = optionalNumber(j, "maximum-file-bytes", 67108864, 1024, 268435456);
        int busy = optionalNumber(s, "busy-timeout-ms", 5000, 250, 30000);
        String scope = text(c, "scope");
        if (!scope.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("scope: use 1-32 lowercase letters, digits, _ or -");
        String host = text(d, "host");
        if (!host.matches("[A-Za-z0-9.-]{1,253}")) throw new IllegalArgumentException("host: hostname or IPv4 required (no JDBC options)");
        String schema = text(d, "database");
        if (!schema.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("database: use 1-64 letters, digits or _");
        String env = text(d, "password-environment");
        if (!env.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("password-environment: invalid variable name");
        String ssl = text(d, "ssl-mode");
        if (!java.util.Set.of("verify-full", "verify-ca", "disable").contains(ssl)) throw new IllegalArgumentException("ssl-mode: verify-full, verify-ca or disable required");
        int workers = number(c, "database-workers", 1, 16);
        int pool = number(d, "maximum-pool-size", 1, 16);
        if (type == StorageType.MYSQL && workers > pool) throw new IllegalArgumentException("database-workers must be <= maximum-pool-size");
        return new Settings(scope, PlayerLanguage.parse(text(c, "default-language")),
                number(c, "cache-maximum-size", 1, 100_000), number(c, "cache-ttl-seconds", 1, 3600),
                workers, number(c, "database-queue-size", 1, 10000), number(c, "shutdown-timeout-seconds", 1, 60),
                flag(i, "placeholderapi"), new Database(host, number(d, "port", 1, 65535), schema,
                text(d, "username"), env, ssl, pool, number(d, "connection-timeout-ms", 250, 30000),
                number(d, "max-lifetime-ms", 30000, 3600000)), new Storage(type, json, maximum, sqlite, busy));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> map, String key, boolean optional) {
        Object value = map.get(key);
        if (value == null && optional) return Map.of();
        if (!(value instanceof Map<?, ?> values) || values.keySet().stream().anyMatch(k -> !(k instanceof String))) {
            throw new IllegalArgumentException("storage." + key + ": mapping required");
        }
        return (Map<String, Object>) values;
    }
    private static int optionalNumber(Map<String, Object> map, String key, int value, int min, int max) {
        return map.containsKey(key) ? number(map, key, min, max) : value;
    }
    private static String relativePath(Object value, String key) {
        if (!(value instanceof String text) || text.isBlank() || Path.of(text).isAbsolute()
                || Path.of(text).normalize().startsWith("..") || Path.of(text).normalize().toString().isEmpty()) {
            throw new IllegalArgumentException(key + ": relative path inside plugin directory required");
        }
        return text;
    }
}
