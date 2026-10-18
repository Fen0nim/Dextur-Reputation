package org.dextur.reputation.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.dextur.reputation.domain.PlayerLanguage;

import static org.dextur.reputation.application.YamlFiles.*;

public record Settings(String scope, PlayerLanguage defaultLanguage, int cacheSize, int cacheSeconds,
        int workers, int queueSize, int shutdownSeconds, boolean placeholderApi,
        Database database) {
    public record Database(String host, int port, String schema, String username,
            String passwordEnvironment, String sslMode, int poolSize, int connectionTimeoutMs,
            int maxLifetimeMs) { }

    public static Settings load(Path directory) throws IOException {
        Map<String, Object> c = read(directory.resolve("config.yml"));
        Map<String, Object> d = read(directory.resolve("storage.yml"));
        Map<String, Object> i = read(directory.resolve("integrations.yml"));
        version(c); version(d); version(i);
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
        if (workers > pool) throw new IllegalArgumentException("database-workers must be <= maximum-pool-size");
        return new Settings(scope, PlayerLanguage.parse(text(c, "default-language")),
                number(c, "cache-maximum-size", 1, 100_000), number(c, "cache-ttl-seconds", 1, 3600),
                workers, number(c, "database-queue-size", 1, 10000), number(c, "shutdown-timeout-seconds", 1, 60),
                flag(i, "placeholderapi"), new Database(host, number(d, "port", 1, 65535), schema,
                text(d, "username"), env, ssl, pool, number(d, "connection-timeout-ms", 250, 30000),
                number(d, "max-lifetime-ms", 30000, 3600000)));
    }
}
