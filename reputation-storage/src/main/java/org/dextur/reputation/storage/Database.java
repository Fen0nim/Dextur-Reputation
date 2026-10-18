package org.dextur.reputation.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.dextur.reputation.application.Settings;

/** Open and close on database workers. Never logs the JDBC URL or credentials. */
public final class Database implements AutoCloseable {
    private final AtomicReference<HikariDataSource> pool = new AtomicReference<>();

    public void open(Settings.Database settings, Map<String, String> environment) throws Exception {
        String password = environment.get(settings.passwordEnvironment());
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Set database password variable: " + settings.passwordEnvironment());
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("DexturReputation");
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.schema()
                + "?sslMode=" + settings.sslMode() + "&connectTimeout=" + settings.connectionTimeoutMs()
                + "&socketTimeout=10000&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        config.setUsername(settings.username());
        config.setPassword(password);
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setValidationTimeout(Math.min(1000, settings.connectionTimeoutMs()));
        config.setMaxLifetime(settings.maxLifetimeMs());
        config.setInitializationFailTimeout(-1);
        HikariDataSource created = new HikariDataSource(config);
        if (!pool.compareAndSet(null, created)) { created.close(); throw new IllegalStateException("Database already open"); }
        try { new SchemaMigrator().migrate(created); }
        catch (Exception e) { close(); throw e; }
    }

    public javax.sql.DataSource dataSource() {
        HikariDataSource value = pool.get();
        if (value == null) throw new IllegalStateException("Database not open");
        return value;
    }
    @Override public void close() {
        HikariDataSource value = pool.getAndSet(null);
        if (value != null) value.close();
    }
}
